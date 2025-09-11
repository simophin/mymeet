use axum::extract::FromRequestParts;
use axum::http::StatusCode;
use axum::http::request::Parts;

#[derive(Debug, Clone)]
pub struct User {
    pub id: String,
    pub name: String,
}

impl<S: Sync> FromRequestParts<S> for User {
    type Rejection = (StatusCode, &'static str);

    async fn from_request_parts(parts: &mut Parts, _state: &S) -> Result<Self, Self::Rejection> {
        let id = parts
            .headers
            .get("X-User-Id")
            .and_then(|v| v.to_str().ok())
            .ok_or_else(|| (StatusCode::BAD_REQUEST, "Invalid user id"))?
            .to_string();

        let name = parts
            .headers
            .get("X-User-Name")
            .and_then(|v| v.to_str().ok())
            .ok_or_else(|| (StatusCode::BAD_REQUEST, "Invalid user name"))?
            .to_string();

        Ok(Self { id, name })
    }
}
