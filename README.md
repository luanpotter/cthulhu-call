cthulhu
=============================

Cthulhu is a very simple proxy that caches all identical requests within a userspace.

It's already deployed in GAE, and you can use it anywhere, anytime, for free!

Or you can deploy your own copy. Just give us a star to let us know ;)

How?
===

Very simple. Make the exact same request you wanted, but instead of your domain, protocol and port, use:

https://cthulhu-call.appspot.com

Then, add 2 headers:

    cthulhu-domain: the original procotol, domain and port (optional) of your request; e.g., https://google.com:8080
    cthulhu-uuid: a uuid-like string to identify your userspace

Cache is persistent forver within the same userspace. If you want to invalated cache, perform any request to the server with your cthulhu-uuid and also this header:

    cthulhu-reset: true

You can always generate a random new UUID!

That's it!

