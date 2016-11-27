#!/bin/sh
echo "SWITCHING APT SOURCE"
sed -i "s/httpredir.debian.org/mirror.aarnet.edu.au/" /etc/apt/sources.list
