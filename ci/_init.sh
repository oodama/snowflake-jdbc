#!/bin/bash -e

export PLATFORM=$(echo $(uname) | tr '[:upper:]' '[:lower:]')
export INTERNAL_REPO=***REMOVED***
if [[ -z "$GITHUB_ACTIONS" ]]; then
    # Use the internal Docker Registry
    export DOCKER_REGISTRY_NAME=$INTERNAL_REPO/docker
    export WORKSPACE=${WORKSPACE:-/tmp}
else
    # Use Docker Hub
    export DOCKER_REGISTRY_NAME=snowflakedb
    export WORKSPACE=$GITHUB_WORKSPACE
fi
mkdir -p $WORKSPACE

export DRIVER_NAME=jdbc

# Build images
BUILD_IMAGE_VERSION=1

# Test Images
TEST_IMAGE_VERSION=1

declare -A BUILD_IMAGE_NAMES=(
)
export BUILD_IMAGE_NAMES

declare -A TEST_IMAGE_NAMES=(
    [$DRIVER_NAME-centos6-default]=$DOCKER_REGISTRY_NAME/client-$DRIVER_NAME-centos6-default-test:$BUILD_IMAGE_VERSION
)
export TEST_IMAGE_NAMES

