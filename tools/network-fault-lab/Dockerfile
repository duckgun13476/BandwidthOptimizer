ARG CERTIFICATE_IMAGE=eclipse-temurin:17-jre-jammy
FROM ${CERTIFICATE_IMAGE} AS certificate-source
FROM debian@sha256:0104b334637a5f19aa9c983a91b54c89887c0984081f2068983107a6f6c21eeb
COPY --from=certificate-source /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
RUN sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources && apt-get update -o APT::Update::Error-Mode=any && apt-get install -y --no-install-recommends openjdk-17-jre-headless iproute2 python3 ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /lab
ENTRYPOINT ["python3", "/lab/fault_lab.py"]
