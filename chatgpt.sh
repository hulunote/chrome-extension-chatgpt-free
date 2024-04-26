#!/bin/bash

function backup() {
    echo "not supported"
}

function deploy() {
    yarn release
    scp unpacked/out/* ubuntu@117.50.190.115:~/ChromeExt/unpacked/out/.
}

function revert() {
    echo "not supported"
}

case $1 in
"backup") backup ;;
"deploy") deploy ;;
"revert") revert ;;
*) usage ;;
esac
