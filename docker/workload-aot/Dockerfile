# Replaces the startup-trained AOT cache of an alf.io image with one trained on a workload (see scripts/train-aot.sh)
ARG BASE_IMAGE
FROM ${BASE_IMAGE}
COPY --chown=alfio alfio.aot /home/alfio/app/alfio.aot
