#!/bin/bash
#
# Copyright 2014 Midokura SARL
# 
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
# 
#     http://www.apache.org/licenses/LICENSE-2.0
# 
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

check_command_availability() {
    if !(("$1" "${2:-\"--version\"}") > /dev/null 2>&1); then
        echo >&2 "$COMMAND: $1 is required to run this script"
        exit 1
    fi
}

mm_dpctl () {
    mm-dpctl --timeout=${TIMEOUT:-60} "$@"
}

# Refer to the following page to know how docker create network namespaces:
#   https://docs.docker.com/articles/networking/#how-docker-networks-a-container
create_netns_symblink() {
    mkdir -p /var/run/netns
    if [ ! -e /var/run/netns/"$PID" ]; then
        ln -s /proc/"$PID"/ns/net /var/run/netns/"$PID"
        trap 'delete_netns_symlink' 0
        for signal in 1 2 3 13 14 15; do
            trap "delete_netns_symlink; trap - $signal; kill -$signal $$" $signal
        done
    fi
}

delete_netns_symlink() {
    rm -f /var/run/netns/"$PID"
 }

add_if() {
    DATAPATH="$1"
    INTERFACE="$2"
    CONTAINER="$3"
    ADDRESS="$4"

    if [ $# -lt 3 ]; then
        echo >&2 "Too few arguments for add-if."
        help
        exit 1
    fi

    if !((mm_dpctl --list-dps | grep "$DATAPATH" > /dev/nul 2>&1) ||
            (mm_dpctl --add-dp "$DATAPATH")); then
        echo >&2 "Failed to create datapathe $DATAPATHE"
        exit 1
    fi

    PID=`docker inspect -f '{{.State.Pid}}' "$CONTAINER"`
    if [ "$PID" = "" ]; then
        echo >&2 "Failed to get the PID of the container $CONTAINER"
        exit 1
    fi

    create_netns_symblink

    # Create a veth pair.
    ID=`uuidgen | sed 's/-//g'`
    IFNAME="${CONTAINER:0:8}${INTERFACE:0:5}"
    sudo ip link add "${IFNAME}" type veth peer name "${IFNAME}_c"

    # Add one end of veth to the datapath.
    if !((mm_dpctl --add-if "${IFNAME}" "$DATAPATH") > /dev/null 2>^1); then
        echo >&2 "Failed to add \"${IFNAME}\" interface to the datapath $DATAPATH"
        ip link delete "${IFNAME}"
        delete_netns_symlink
        exit 1
    fi

    ip link set "${IFNAME}" up

    # Move "{IFNAME}_c" inside the container and changes its name.
    ip link set "${IFNAME}_c" netns "$PID"
    ip netns exec "$PID" ip link set dev "${IFNAME}_c" name "${INTERFACE}"
    ip netns exec "$PID" ip link set "$INTERFACE" up

    if [ -n "$ADDRESS" ]; then
        ip netns exec $PID ip add add "$ADDRESS" dev "${INTERFACE}"
    fi

    delete_netns_symlink
}

del_if() {
    DATAPATH="$1"
    INTERFACE="$2"
    CONTAINER="$3"

    if [ "$#" -lt 3 ]; then
        help
        exit 1
    fi

    IFNAME="${CONTAINER:0:8}${INTERFACE:0:5}"
    PORT=`mm_dpctl --show-dp midonet \
            | grep -P "Port \#\d+ \"$IFNAME\"" \
            | awk -F '"' '{print $2}'`
    if [ "$PORT" = "" ]; then
        echo >&2 "Failed to find any attached port in $DATAPATH" \
            "for CONTAINER=$CONTAINER and INTERFACE=$INTERFACE"
        exit 1
    fi

    mm_dpctl --delete-if "$PORT" "$DATAPATH"

    ip link delete "$PORT"
}

del_ifs() {
    DATAPATH="$1"
    CONTAINER="$2"
    if ["$#" -lt 2 ]; then
        help
        exit 1
    fi

    PORTS=`mm_dpctl --show-dp midonet | grep -P "Port \#\d+" | awk -F '"' '{print $2}'`
    if [ -z "$PORTS" ]; then
        exit 0
    fi

    for PORT in $PORTS; do
        mm_dpctl --delete-if "$PORT" "$DATAPATH"
        ip link delete "$PORT"
    done
}

help() {
    cat <<EOF
${COMMAND}: Integrates the network interfaces into MidoNet.
usage: ${COMMAND} COMMAND

Commands:
  add-if DATAPATH INTERFACE CONTAINER [ADDRESS]
          Adds INTERFACE inside CONTAINER and connects it as a port in the given
          DATAPATH. It also adds the address to the interface if it's given.
          ${COMMAND} add-if dp0 veth8a72ecc1-5a 5506de2b643b
  del-if DATAPATH INTERFACE CONTAINER
          Deletes INTERFACE inside CONTAINER and removes its connection to the
          associated DATAPATH.
          ${COMMAND} del-if  dp0 veth8a72ecc1-5a 5506de2b643b
  del-ifs DATAPATH CONTAINER
          Removes all MidoNet interfaces associated with the DATAPATH from
          CONTAINER.
          ${COMMAND} del-ifs dp0 5506de2b643b
Options:
  -h, --help   display this help message.
EOF
}

COMMAND=$(basename $0)
# check_command_availability mm-dpctl --list-dps
check_command_availability ip netns
check_command_availability docker
check_command_availability uuidgen

if [ $# -eq 0 ]; then
    help
    exit 0
fi

case $1 in
    "add-if")
        shift
        add_if "$@"
        exit 0
        ;;
    "del-if")
        shift
        del_if "$@"
        exit 0
        ;;
    "del-ifs")
        shift
        del_ifs "$@"
        exit 0
        ;;
    -h | --help)
        shift
        help
        exit 0
        ;;
    *)
        echo >&2 "$0: unknown command \"$1\" (use --help for help)"
        exit 1
        ;;
esac

