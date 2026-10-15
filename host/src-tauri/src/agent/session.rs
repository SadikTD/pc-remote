//! One phone connection: TLS → WebSocket `/v1` → pair|hello → message loop.

use super::{Ctx, Event, Out, CAPS};
use crate::proto::{ClientMsg, ServerMsg};
use futures_util::{SinkExt, StreamExt};
use std::sync::Arc;
use tokio::net::TcpStream;
use tokio::sync::mpsc;
use tokio::time::timeout;
use tokio_tungstenite::tungstenite::handshake::server::{ErrorResponse, Request, Response};
use tokio_tungstenite::tungstenite::http::StatusCode;
use tokio_tungstenite::tungstenite::Message;

type Ws = tokio_tungstenite::WebSocketStream<tokio_rustls::server::TlsStream<TcpStream>>;

async fn send(ws: &mut Ws, m: &ServerMsg) -> bool {
    ws.send(Message::text(m.json())).await.is_ok()
}

async fn fail(ws: &mut Ws, code: &str, msg: &str) {
    let _ = send(ws, &ServerMsg::error(code, msg, None)).await;
    let _ = ws.close(None).await;
}

/// The next text frame, or None on close, error or `idle` silence.
async fn next_text(ws: &mut Ws, idle: std::time::Duration) -> Option<Option<String>> {
    match timeout(idle, ws.next()).await {
        Ok(Some(Ok(Message::Text(t)))) => Some(Some(t.to_string())),
        Ok(Some(Ok(Message::Close(_)))) | Ok(Some(Err(_))) | Ok(None) | Err(_) => None,
        Ok(Some(Ok(_))) => Some(None), // binary/ping/pong: activity, nothing to parse
    }
}

pub(crate) async fn run(tcp: TcpStream, acceptor: tokio_rustls::TlsAcceptor, ctx: Arc<Ctx>) {
    let Ok(Ok(tls)) = timeout(ctx.idle, acceptor.accept(tcp)).await else { return };
    #[allow(clippy::result_large_err)] // signature fixed by tungstenite
    let only_v1 = |req: &Request, resp: Response| -> Result<Response, ErrorResponse> {
        if req.uri().path() == "/v1" {
            Ok(resp)
        } else {
            let mut e = ErrorResponse::new(None);
            *e.status_mut() = StatusCode::NOT_FOUND;
            Err(e)
        }
    };
    let Ok(Ok(mut ws)) = timeout(ctx.idle, tokio_tungstenite::accept_hdr_async(tls, only_v1)).await else { return };

    let first = loop {
        match next_text(&mut ws, ctx.idle).await {
            None => return,
            Some(None) => continue,
            Some(Some(t)) => break serde_json::from_str::<ClientMsg>(&t).ok(),
        }
    };
    let (tx, mut rx) = mpsc::unbounded_channel();
    let device = match first {
        Some(ClientMsg::Pair { otp, device, pin }) => {
            if !ctx.store.lock().unwrap().take_otp(&otp) {
                return fail(&mut ws, "bad_otp", "pairing code is wrong or expired").await;
            }
            let Ok((id, token)) = ctx.store.lock().unwrap().add_device(&device) else {
                return fail(&mut ws, "internal", "could not save the device").await;
            };
            let _ = ctx.events.send(Event::Paired { device: id.clone() });
            let pin_tx = tx.clone();
            let submit = ctx.handlers.submit_pin(pin, device);
            tokio::spawn(async move {
                if !submit.await {
                    let _ = pin_tx.send(Out::Msg(ServerMsg::error("pin_timeout", "Sunshine did not accept the PIN", Some("pair"))));
                }
            });
            let pc = ctx.handlers.pc_info().await;
            if !send(&mut ws, &ServerMsg::Paired { token, pc }).await {
                return;
            }
            id
        }
        Some(ClientMsg::Hello { token, .. }) => {
            let auth = ctx.store.lock().unwrap().auth(&token);
            match auth {
                Ok(id) => {
                    let pc = ctx.handlers.pc_info().await;
                    let caps = CAPS.iter().map(|c| c.to_string()).collect();
                    if !send(&mut ws, &ServerMsg::Welcome { v: 1, pc, caps }).await {
                        return;
                    }
                    id
                }
                Err(code) => return fail(&mut ws, code, "this phone is not paired").await,
            }
        }
        _ => return fail(&mut ws, "unauthorized", "first message must be pair or hello").await,
    };

    let sid = ctx.register(&device, tx);
    let mut input = ctx.handlers.input();
    loop {
        tokio::select! {
            m = next_text(&mut ws, ctx.idle) => match m {
                None => break,
                Some(None) => {}
                Some(Some(t)) => {
                    // Unknown types and malformed messages are ignored, per protocol.
                    let Ok(msg) = serde_json::from_str::<ClientMsg>(&t) else { continue };
                    if let Some(reply) = dispatch(&ctx, input.as_mut(), msg.sanitize()) {
                        if !send(&mut ws, &reply).await { break }
                    }
                }
            },
            o = rx.recv() => match o {
                Some(Out::Msg(m)) => if !send(&mut ws, &m).await { break },
                Some(Out::Close(code)) => { fail(&mut ws, code, "this phone was removed on the PC").await; break }
                None => break,
            },
        }
    }
    drop(input); // releases held keys/buttons
    ctx.unregister(sid);
}

fn dispatch(ctx: &Ctx, input: &mut dyn super::InputSink, m: ClientMsg) -> Option<ServerMsg> {
    let of = m.t();
    let err = |code: &str, msg: &str| Some(ServerMsg::error(code, msg, Some(of)));
    match m {
        ClientMsg::Ping => Some(ServerMsg::Pong),
        ClientMsg::MouseMove { .. }
        | ClientMsg::MouseButton { .. }
        | ClientMsg::MouseScroll { .. }
        | ClientMsg::Key { .. }
        | ClientMsg::Text { .. } => input.handle(&m).err().and_then(|c| err(c, "invalid input")),
        ClientMsg::Power { a } => match ctx.handlers.power(&a) {
            Ok(()) => Some(ServerMsg::Ok { of: of.into() }),
            Err(c) => err(c, "power action failed"),
        },
        ClientMsg::Pair { .. } | ClientMsg::Hello { .. } => err("bad_request", "already authenticated"),
        _ => err("unsupported", "not supported by this PC yet"),
    }
}
