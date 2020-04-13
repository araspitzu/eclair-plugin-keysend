## KeySend eclair plugin

This is a plugin for [eclair](github.com/ACINQ/eclair) that implements the [`keysend`](https://wiki.ion.radar.tech/tech/research/sphinx-send) 
functionality. Keysend refers to sending payment without having a BOLT11 invoice at the recipient side, instead 
using the new TLV format we can attach the payment preimage inside the innermost layer of the onion as a custom 
TLV record. The receiver can accept the payment because it has the preimage from the TLV records. Please note that
the `keysend` functionality is non standard and still experimental.

This plugin has been tested with lnd-0.9.0-beta1.

### Installation
The plugin can be built locally or downloaded from the release page of this repo, it's a fat jar that must be 
passed as argument to eclair when it's launched, see the [instructions](https://github.com/ACINQ/eclair#plugins).

### Usage

The plugin supprts both sending and receiving `keysend` payments, in order to send we expose a new http interface 
with an additional endpoint:

|              | METHOD | Parameter (form_data) | Action                                                      |
|--------------|--------|-----------------------|-------------------------------------------------------------|
| /keysend | POST   | amountMsat, nodeId          | Send a `keysend` payment to the recipient      |

Note that it's currently impossible for eclair plugins to add new HTTP-RPC endpoints, so this plugin listens
on a different port than the standard eclair API. You can configure the port by setting the
configuration key `keysend.api.port` but if none is set the plugin will default to the eclair API port + 1.
The same authentication credentials of the regular eclair API is necessary to use the plugin APIs.