#!/usr/bin/env bash
set -e

CONFIG_FILE=./stub-oidc-broker.yml

cd "$(dirname "$0")"

LOCAL_IP="$(ipconfig getifaddr en0)"
export REDIS_URI="redis://${LOCAL_IP}:6381"
export APPLICATION_PORT=5510
export STUB_BROKER_URI=http://localhost:5510
export STUB_OP_URI=http://localhost:6610
export ADMIN_PORT=5511
export STUB_TRUSTFRAMEWORK_RP=http://localhost:4412
export VERIFIABLE_CREDENTIAL_URI=http://localhost:3334
export REDIS_DATABASE="/2"
export SCHEME=2

./gradlew installDist

trap "docker container stop clientRedis1" EXIT
docker run --name clientRedis1 -d -p 6381:6379 --rm redis

./build/install/stub-oidc-broker/bin/stub-oidc-broker server $CONFIG_FILE