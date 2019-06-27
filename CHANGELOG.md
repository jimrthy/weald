# 0.0.4 - 2019-JUN-26

* Breaking change: frereth.weald.logging/do-sync-clock now operates on
  a ::frereth.weald.specs/lamport (which is really just a nat-int?)
  rather than a full-blown ::frereth.weald/specs/state (which happens
  to include that ::lamport as a member). I'm trying to make it more
  generally useful so things that aren't log functions can tap
  into this functionality. Because, really, I need it for networking
  endpoints.
* Improved clojurescript support
* Added log-atomically! and flush-atomically! because it's
  significantly more convenient to accumulate the log entries in
  an atom than using the fully functional approach with which I
  started.

# 0.0.3 - 2018-DEC-21

Clean things up to make it usable/useful by a project that pulls it from
clojars rather than just using the local maven repo.

# 0.0.2 - 2018-NOV-23

Set it up to be able to push to clojars

# v0.0.1 - 2018-NOV-20

Extracted the basic functionality out of the frereth-cp project.

Added limited support for clojurescript and core.async.
