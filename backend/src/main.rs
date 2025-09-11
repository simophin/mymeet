use axum::Router;
use axum::routing::post;

mod proto;

async fn handle_room(Path(room_id): Path<String>) -> impl IntoResponse {
    todo!()
}

#[tokio::main]
async fn main() {
    let _ = dotenvy::dotenv();
    tracing_subscriber::fmt::init();

    Router::new()
        .route("/rooms/{room_id}", post(handle_room))
}
