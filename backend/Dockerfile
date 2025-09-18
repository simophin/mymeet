FROM rust

RUN apt-get update && \
    apt-get install -y protobuf-compiler

WORKDIR /usr/src/myapp
COPY . .
RUN cargo build --release 

FROM debian
COPY --from=0 /usr/src/myapp/target/release/backend /usr/local/bin/backend

ENV LISTEN=0.0.0.0:3000
ENV RUST_LOG=info
EXPOSE 3000
CMD ["/usr/local/bin/backend"]
