#!/bin/bash
set --
if [ -f "$HOME/.bashrc" ]; then . "$HOME/.bashrc"; fi
if [ -f "$HOME/.profile" ]; then . "$HOME/.profile"; fi
if [ -f "$HOME/.bash_profile" ]; then . "$HOME/.bash_profile"; fi
echo "Environment reloaded."
