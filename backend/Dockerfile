FROM rust

RUN apt-get update && \
    apt-get install -y musl protobuf && \
    rustup target add x86_64-unknown-linux-musl

WORKDIR /usr/src/myapp
COPY . .
RUN cargo build --release --target x86_64-unknown-linux-musl

FROM scratch
COPY --from=0 /usr/src/myapp/target/x86_64-unknown-linux-musl/release/backend /usr/local/bin/backend

ENV LISTEN 0.0.0.0:3000
EXPOSE 3000
CMD ["/usr/local/bin/backend"]
