//! One phone connection: TLS → WebSocket `/v1` (or a relayed connection) → pair|hello → message loop.

use super::{Ctx, Event, Out, Outbox, MAX_FRAME};
use crate::proto::{ClientMsg, ServerMsg};
use futures_util::{Sink, SinkExt, Stream, StreamExt};
use std::sync::Arc;
use tokio::net::TcpStream;
use tokio::sync::mpsc;
use tokio::time::timeout;
use tokio_tungstenite::tungstenite::handshake::server::{ErrorResponse, Request, Response};
use tokio_tungstenite::tungstenite::http::StatusCode;
use tokio_tungstenite::tungstenite::protocol::WebSocketConfig;
use tokio_tungstenite::tungstenite::Message;

/// Either half of a phone connection: a direct WebSocket or a relayed one.
pub(crate) trait Rx: Stream<Item = Message> + Unpin + Send {}
impl<T: Stream<Item = Message> + Unpin + Send> Rx for T {}
pub(crate) trait Tx: Sink<Message> + Unpin + Send {}
impl<T: Sink<Message> + Unpin + Send> Tx for T {}

async fn send(ws: &mut impl Tx, m: &ServerMsg) -> bool {
    ws.send(Message::text(m.json())).await.is_ok()
}

async fn fail(ws: &mut impl Tx, code: &str, msg: &str) {
    let _ = send(ws, &ServerMsg::error(code, msg, None)).await;
    let _ = ws.send(Message::Close(None)).await;
}

enum Frame {
    Text(String),
    Bin(Vec<u8>),
    Other, // ping/pong: activity only
}

/// The next frame, or None on close, error or `idle` silence.
async fn next_frame(ws: &mut impl Rx, idle: std::time::Duration) -> Option<Frame> {
    match timeout(idle, ws.next()).await {
        Ok(Some(Message::Text(t))) => Some(Frame::Text(t.to_string())),
        Ok(Some(Message::Binary(b))) => Some(Frame::Bin(b.to_vec())),
        Ok(Some(Message::Close(_))) | Ok(None) | Err(_) => None,
        Ok(Some(_)) => Some(Frame::Other),
    }
}

/// `remote` for a paired phone, when a Worker is configured.
fn remote(ctx: &Ctx, dev: &str) -> Option<ServerMsg> {
    let url = crate::remote::url()?;
    let ticket = crate::store::b64url(&ctx.store.lock().unwrap().ticket(dev));
    Some(ServerMsg::Remote { url: url.into(), dev: dev.into(), ticket })
}

fn ws_config() -> WebSocketConfig {
    WebSocketConfig::default().max_message_size(Some(MAX_FRAME)).max_frame_size(Some(MAX_FRAME))
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
    let Ok(Ok(ws)) = timeout(ctx.idle, tokio_tungstenite::accept_hdr_async_with_config(tls, only_v1, Some(ws_config()))).await else { return };
    let (tx, rx) = ws.split();
    // a read error ends the connection like a close
    let rx = rx.take_while(|r| std::future::ready(r.is_ok())).filter_map(|r| std::future::ready(r.ok()));
    serve(rx, tx, ctx).await
}

/// The agent protocol over any connection; returns when it ends.
pub(crate) async fn serve(mut inc: impl Rx, mut ws: impl Tx, ctx: Arc<Ctx>) {
    let first = loop {
        match next_frame(&mut inc, ctx.idle).await {
            None => return,
            Some(Frame::Text(t)) => break serde_json::from_str::<ClientMsg>(&t).ok(),
            Some(Frame::Other) => continue,
            Some(Frame::Bin(_)) => break None,
        }
    };
    let (tx, mut rx) = mpsc::unbounded_channel();
    let device = match first {
        Some(ClientMsg::Pair { otp, device, .. }) => {
            let device: String = device.trim().chars().take(40).collect();
            if !ctx.store.lock().unwrap().take_otp(&otp) {
                return fail(&mut ws, "bad_otp", "pairing code is wrong or expired").await;
            }
            let Ok((id, token)) = ctx.store.lock().unwrap().add_device(&device) else {
                return fail(&mut ws, "internal", "could not save the device").await;
            };
            let _ = ctx.events.send(Event::Paired { device: id.clone() });
            let pc = ctx.handlers.pc_info().await;
            if !send(&mut ws, &ServerMsg::Paired { token, pc }).await {
                return;
            }
            if let Some(m) = remote(&ctx, &id) {
                let _ = send(&mut ws, &m).await;
            }
            id
        }
        Some(ClientMsg::Hello { token, .. }) => {
            let auth = ctx.store.lock().unwrap().auth(&token);
            match auth {
                Ok(id) => {
                    let pc = ctx.handlers.pc_info().await;
                    if !send(&mut ws, &ServerMsg::Welcome { v: 1, pc, caps: ctx.handlers.caps() }).await {
                        return;
                    }
                    if let Some(m) = remote(&ctx, &id) {
                        let _ = send(&mut ws, &m).await;
                    }
                    id
                }
                Err(code) => return fail(&mut ws, code, "this phone is not paired").await,
            }
        }
        _ => return fail(&mut ws, "unauthorized", "first message must be pair or hello").await,
    };

    let (bin_tx, mut bin_rx) = mpsc::channel(4);
    let mut session = ctx.handlers.session(&device, Outbox { ctl: tx.clone(), bin: bin_tx });
    let sid = ctx.register(&device, tx);
    loop {
        tokio::select! {
            f = next_frame(&mut inc, ctx.idle) => {
                let reply = match f {
                    None => break,
                    Some(Frame::Other) => None,
                    Some(Frame::Bin(b)) => session.on_binary(&b),
                    // Unknown types and malformed messages are ignored, per protocol.
                    Some(Frame::Text(t)) => match serde_json::from_str::<ClientMsg>(&t) {
                        Ok(ClientMsg::Ping) => Some(ServerMsg::Pong),
                        Ok(m @ (ClientMsg::Pair { .. } | ClientMsg::Hello { .. })) => {
                            Some(ServerMsg::error("bad_request", "already authenticated", Some(m.t())))
                        }
                        Ok(m) => session.on_msg(m.sanitize()),
                        Err(_) => None,
                    },
                };
                if let Some(r) = reply {
                    if !send(&mut ws, &r).await { break }
                }
            },
            o = rx.recv() => match o {
                Some(Out::Msg(m)) => if !send(&mut ws, &m).await { break },
                Some(Out::Close(code)) => { fail(&mut ws, code, "this phone was removed on the PC").await; break }
                None => break,
            },
            Some(b) = bin_rx.recv() => if ws.send(Message::binary(b)).await.is_err() { break },
        }
    }
    ctx.unregister(sid);
    drop(session); // releases held keys/buttons, stops subscriptions
}
