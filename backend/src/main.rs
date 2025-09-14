use crate::room::{AppState, handle_room_request};
use axum::Router;
use axum::routing::get;
use clap::Parser;
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::net::TcpListener;

mod proto;
mod room;
mod user;

#[derive(Parser)]
struct CliOptions {
    #[arg(default_value = "0.0.0.0:3000", env)]
    /// Address to listen to
    listen: SocketAddr,
}

#[tokio::main]
async fn main() {
    let _ = dotenvy::dotenv();
    tracing_subscriber::fmt::init();

    let CliOptions { listen } = CliOptions::parse();

    let app = Router::new()
        .route("/rooms/{room}", get(handle_room_request))
        .with_state(Arc::new(AppState::default()));

    // run our app with hyper, listening globally on port 3000
    let listener = TcpListener::bind(listen).await.unwrap();

    tracing::info!("Server listening on {:?}", listener.local_addr().unwrap());

    axum::serve(listener, app).await.unwrap();
}
