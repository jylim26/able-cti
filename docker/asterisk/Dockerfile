# Asterisk 22 (LTS) 개발용 이미지 — 소스 빌드, ODBC(PostgreSQL) 포함
FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential ca-certificates curl file pkg-config \
    libedit-dev libjansson-dev libsqlite3-dev libssl-dev libxml2-dev \
    uuid-dev libcap-dev \
    unixodbc unixodbc-dev odbc-postgresql \
    && rm -rf /var/lib/apt/lists/*

ARG ASTERISK_TARBALL=asterisk-22-current.tar.gz

RUN curl -fsSL "https://downloads.asterisk.org/pub/telephony/asterisk/${ASTERISK_TARBALL}" \
        -o /tmp/asterisk.tar.gz \
    && mkdir -p /usr/src/asterisk \
    && tar -xzf /tmp/asterisk.tar.gz -C /usr/src/asterisk --strip-components=1 \
    && rm /tmp/asterisk.tar.gz

WORKDIR /usr/src/asterisk

RUN ./configure --with-pjproject-bundled \
    && make -j"$(nproc)" \
    && make install \
    && ldconfig \
    && rm -rf /usr/src/asterisk

# 설정은 compose에서 /etc/asterisk 로 마운트
EXPOSE 5060/udp 5038
CMD ["asterisk", "-f", "-vvv"]
