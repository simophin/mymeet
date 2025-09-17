use crate::proto::messages::{ClientMessage, Command, GroupStateUpdate, MemberState, client_message, ServerMessage, server_message};
use crate::user::User;
use anyhow::{Context, format_err};
use axum::extract::ws::{Message, WebSocket};
use axum::extract::{Path, State, WebSocketUpgrade};
use axum::response::Response;
use parking_lot::Mutex;
use prost::Message as ProstMessage;
use std::collections::HashMap;
use std::sync::Arc;
use tokio::select;
use tokio::sync::{mpsc, watch};
use tokio::task::JoinSet;
use tracing::instrument;

struct ClientState {
    name: String,
    message_sender: mpsc::Sender<ClientMessage>,
}

enum RoomCommand {
    JoinRoom(User, mpsc::Sender<ClientMessage>),
    LeaveRoom {
        user_id: String,
    },
    UserCommand {
        from_user_id: String,
        to_user_id: String,
        cmd: Command,
    },
}

struct RoomState {
    commands_tx: mpsc::Sender<RoomCommand>,
    clients_rx: watch::Receiver<HashMap<String, ClientState>>,
    _join_set: JoinSet<anyhow::Result<()>>,
}

impl RoomState {
    fn new() -> Self {
        let (commands_tx, mut commands_rx) = mpsc::channel(24);
        let (clients_tx, clients_rx) = watch::channel(HashMap::new());
        let mut join_set = JoinSet::new();

        join_set.spawn(async move {
            while let Some(cmd) = commands_rx.recv().await {
                match cmd {
                    RoomCommand::JoinRoom(User { id, name }, message_sender) => {
                        let state = ClientState {
                            name,
                            message_sender,
                        };

                        clients_tx.send_if_modified(move |m| m.insert(id, state).is_none());
                    }

                    RoomCommand::LeaveRoom { user_id } => {
                        tracing::debug!("User {user_id} leaving room");
                        clients_tx.send_if_modified(|m| m.remove(&user_id).is_some());
                    }

                    RoomCommand::UserCommand {
                        from_user_id,
                        to_user_id,
                        cmd,
                    } => {
                        tracing::debug!(
                            from_user_id,
                            ?cmd,
                            "Received user command"
                        );

                        let target_sender = clients_tx
                            .borrow()
                            .get(&to_user_id)
                            .map(|s| s.message_sender.clone());

                        match target_sender {
                            Some(s) => {
                                let _ = s
                                    .send(ClientMessage {
                                        from_user: Some(from_user_id),
                                        content: Some(client_message::Content::Command(cmd)),
                                    })
                                    .await;
                            }

                            None => {
                                tracing::warn!("Sender not found for user id {to_user_id}");
                            }
                        }
                    }
                }
            }

            Ok(())
        });

        Self {
            commands_tx,
            clients_rx,
            _join_set: join_set,
        }
    }
}

#[derive(Default)]
pub struct AppState {
    rooms: Mutex<HashMap<String, RoomState>>,
}

#[instrument(skip(app_state, ws), ret)]
pub async fn handle_room_request(
    Path(room): Path<String>,
    State(app_state): State<Arc<AppState>>,
    user: User,
    ws: WebSocketUpgrade,
) -> Response {
    ws.on_upgrade(async move |ws| handle_websocket(room, ws, user, app_state).await)
}

#[instrument(skip(ws, state))]
async fn handle_websocket(room: String, mut ws: WebSocket, user: User, state: Arc<AppState>) {
    let (commands_tx, mut clients_rx) = {
        let mut guard = state.rooms.lock();
        let state = guard.entry(room).or_insert_with(RoomState::new);
        (state.commands_tx.clone(), state.clients_rx.clone())
    };

    let (message_tx, mut message_rx) = mpsc::channel(24);
    let _ = commands_tx
        .send(RoomCommand::JoinRoom(user.clone(), message_tx))
        .await;

    let _ = ws.send(recv_group_state_update(&clients_rx)).await;

    let r = loop {
        select! {
            Some(m) = message_rx.recv() => {
                if let Err(e) = ws.send(Message::Binary(m.encode_to_vec().into())).await {
                    break Err(format_err!("Error sending message to websocket: {}", e));
                }
            }

            Some(msg) = recv_command_from_websocket(&mut ws) => {
                match msg {
                    Ok(ServerMessage { to_user, content}) => {
                        if Some(&user.id) == to_user.as_ref() {
                            tracing::warn!("Ignoring command sent to self");
                            continue;
                        }

                        let Some(to_user_id) = to_user else {
                            tracing::warn!("Ignoring command with no target user");
                            continue;
                        };

                        let Some(server_message::Content::Command(cmd)) = content else {
                            tracing::warn!("Ignoring command with no content");
                            continue;
                        };

                        let _ = commands_tx.send(RoomCommand::UserCommand {
                            from_user_id: user.id.clone(),
                            to_user_id,
                            cmd,
                        }).await;
                    }

                    Err(e) => break Err(e),
                }
            }

            Ok(_) = clients_rx.changed() => {
                tracing::debug!("Client states changed");
                if let Err(e) = ws.send(recv_group_state_update(&clients_rx)).await {
                    break Err(format_err!("Error sending group state update to websocket: {}", e));
                }
            }

            else => break anyhow::Ok(()),
        }
    };

    if let Err(e) = &r {
        tracing::error!(?e, "Error handling room logic")
    } else {
        tracing::info!("WebSocket closed normally");
    }

    let _ = commands_tx
        .send(RoomCommand::LeaveRoom { user_id: user.id })
        .await;
}

fn recv_group_state_update(rx: &watch::Receiver<HashMap<String, ClientState>>) -> Message {
    let buf = ClientMessage {
        from_user: None,
        content: Some(client_message::Content::StateUpdate(GroupStateUpdate {
            members: rx
                .borrow()
                .iter()
                .map(|(k, v)| {
                    (
                        k.clone(),
                        MemberState {
                            name: v.name.clone(),
                        },
                    )
                })
                .collect(),
        })),
    }
    .encode_to_vec();

    Message::Binary(buf.into())
}

async fn recv_command_from_websocket(ws: &mut WebSocket) -> Option<anyhow::Result<ServerMessage>> {
    while let Some(msg) = ws.recv().await {
        match msg {
            Ok(Message::Binary(binary)) => {
                return Some(ServerMessage::decode(binary.as_ref()).context("Error decoding ServerMessage"));
            }
            Ok(m) => {
                tracing::warn!(?m, "Ignoring websocket message");
                continue;
            }
            Err(e) => return Some(Err(anyhow::Error::new(e))),
        }
    }

    None
}
