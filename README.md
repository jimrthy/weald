# Weald

**weald** noun
\ ˈwēld  \
Definition of Weald
1. : a heavily wooded area : FOREST

the Weald of Kent
2. : a wild or uncultivated usually upland region

A bunch of logs waiting to be cut

[![Clojars Project](https://img.shields.io/clojars/v/frereth/weald.svg)](https://clojars.org/frereth/weald)

## Running

### CIDER interaction

From your shell:

```sh
    > ./boot.sh cider-repl
```

Then, from emacs, use

    M-x cider-connect

to localhost on port 32767.

(I've had issues trying to use cider-jack-in over SSH, but that will probably work fine for most).

That will put you in the `user` ns.

### Testing

From a clojure REPL in the boot.user ns, run either/both:

#### Clojure

```clojure
(boot (testing) (test))
```

#### Clojurescript

```clojure
(boot (testing) (test-cljs))
```

## Motivation

### Logging is Painful

If you've spent much time setting up server development environments,
you already know that logging is a contentious thing.

Do you forward messages over TCP or UDP?

Do you keep a copy locally?

How do you fine-tune the balance between the log messages you have to
have to debug problems that show up in production, versus coping with
potentially gigabytes of log files every day?

Dealing with unstructured text is painful. At best, you need to add some
third party application to index your logs so you can do some sort of
structured full-text search to try to get any inkling about what you're
trying to find.

The options get weirder when you start dealing with docker containers
that may very well need extra libraries and applications to actually
deal with the output.

### It's Worse on the JVM

Which "standard" logging library should you use?

* Log4j?
* Logback?
* Old java.util.logging?
* Apache Commons Logging?
* JVM 9's Unified Logging?
* SLF4J?

This gets even worse in clojure. We have:

[clojure/tools.logging](https://github.com/clojure/tools.logging) and
[ptaoussanis/timbre](https://github.com/ptaoussanis/timbre) are the top
two google search results.

[clojure toolbox](https://www.clojure-toolbox.com/) also lists
[ring logger](https://github.com/nberger/ring-logger) and
[unilog](https://github.com/pyr/unilog).

[Pedestal](https://github.com/pedestal/pedestal) has its own layer of
macros that turn out to be a wrapper over slf4j.

That's another problem with clojure logging: almost all of it's built
around macros. That obviously isn't a serious problem, but it feels
like overkill.

### Summing Up

All these options may be great for people writing the top level apps and
the dev ops people who have to actually consolidate all this variety,
but it's just annoying for someone trying to write a shared library.

I've been thinking about this for several years now, and I'd started
fiddling with these basic ideas when I ran across
[Logging: Change Your Mind](https://juxt.pro/blog/posts/logging.html).

That article convinced me that the idea isn't completely crazy, and
so I've run with it.

## Usage

Maven coordinates:

```clojure
[com.frereth/weald "version"]
```

Run

```clojure
(require '[frereth-weald.logging :as log])
```

or add that to your ns.

### Globally

Initialize a Log State:

```clojure
(def log-state (log/init "Meaningful Identifier"))
```

Add log entries to it:

```clojure
(alter-var-root #'log-state #(log/info %
                                       ::location-identifier
                                       "Message summary"
                                       {::details "about environment"}))
```

Create a Logger instance that writes to STDOUT:

```clojure
(def logger (log/std-out-log-factory))
```

Other built-in Logger options are:

* `(async-log-factory async-chan)`: writes entries to async-chat
* `(file-writer-factory file-name)`: opens file-name and writes to it
* `(std-err-log-factory)`: writes to STDERR
* `(stream-log-factory stream)`: writes to stream
* `(composite-log-factory logger-seq)`: writes to multiple loggers

Clear the accumulated log entries and flush them to STDOUT:

```clojure
(alter-var-root #'log-state #(log/flush-logs logger %)
```

Fork an empty copy with the same lamport clock:

```clojure
(let [child (log/clean-fork log-state ::child-context-marker)]
   ...)
```

Consolidate the Lamport clocks between two log-state instances:

```clojure
(let [log1 (log/info log1 ::foo)
      log2 (log/info log2 ::foo)
      [log1 log2] (log/synchronize log1 log2)]
  ...)
```

### System/Component

Calling `alter-var-root` for every log message has its pros and cons.

More often, it will make more sense for both the log-state and logger
to be part of your [System](https://github.com/stuartsierra/component).

In this case, you'll wind up writing a lot of functions that look like

```clojure
(defn foo
  [{:keys [:log-state
           :logger]
    :as system}]
  (let [log-state (log/debug log-state ::foo "top")
        {:keys [:log-state]} (bar (assoc system
                                         :log-state log-state))
        {:keys [:log-state]} (baz (assoc system
                                         :log-state log-state))
        log-state (log/flush-logs! logger log-state)]
    (assoc state
           :log-state log-state)))
```

### Logging Atoms

One compromise between those two extremes is to use a
`:frereth.weald.specs/log-state-atom` as your component instead.

Then call something like
`(frereth.weald.logging/log-atomically! log-atom
frereth.weald.logging/${logging-function}
${normal-log-function-arguments})` to
accumulate log entries until you're ready to flush them
using `(frereth.weald.logging/flush-atomically! logger log-atom)`.

#### Explanation

A lot of times, the functions that need logging the most are the ones
that get called for their side-effects.

In general, in clojure, we tend to have those functions return nil to
emphasize their special dangerous nature. That quits working when you
need to return an updated log state.

This can also get annoying when you're using things like thrush
operators but your functions need to return both the thing you care
about and the accumulated log-state.

## More Details

I've broken the basic idea of logging into 2 pieces:

### Log State

This is really just a simple data structure that accumulates log
messages and tracks its view of the
[Lamport Clock](https://amturing.acm.org/p558-lamport.pdf).

In case you wind up with multiple log state instances flying around
at the same time, each has its own context (which can be pretty much
anything you like) that you must supply at creation time to identify
it.

Mostly, you'll wind up calling log functions on a log-state:

* trace
* debug
* info
* warn
* error
* exception

Most of these work similarly to what you hopefully expect.

They take 2 to 4 parameters:

1. log-state
1. label

   This is something to provide context about where the log was written.
   If nothing else, the name of the current function is often helpful.
1. Message string

   In general, keep this short. It's arbitrary unstructured text, and
   part of the goal is getting away from that.
1. Details

   Arbitrary hashmap. At least it's something you can
   interact with programmatically via the REPL.

(exception) is special and different. It takes a Throwable instance
as its second parameter and dumps all the gory details about it (like
the stack trace).

In general, you'll wind up passing the log-state around as a parameter
to everything, and adding a modified version of it to all your return
values.

This may very well be taking functional purity too far.

### Logger

This is a Protocol for performing the actual side-effects.

For pretty much anything more interesting than writing to
an output stream, you'll need to implement them.

It consists of 2 methods:

* `log!`

  This gets called with each individual log entry to send it to your
  real logging backend.

* `flush!`

  Tells the logging backend to write the entries that have been queued
  up using log!

Calling (flush!) is also the mechanism for synchronizing the Lamport
clocks. flush-logs! is part of a lexical closure that tracks the
Loggers' Lamport clock. The log-state that it returns will have its
::lamport set to

```clojure
(inc (max (::lamport log-state) logger-clock))
```

### Output

Currently, the logs all get written as EDN hashmaps. The keys for each
hashmap are:

* ::log/current-thread
* ::log/details (optional)
* ::log/label
* ::log/lamport
* ::log/message (optional)
* ::log/level
* ::log/time (this is the current System time in milliseconds)

## Status

The implementation is currently pretty experimental. I don't see the
main interface changing much, since the fundamental idea is pretty
simple and well established.

I can definitely see places where macros could be useful, especially
around exception handling. But that seems like something much bigger
and more invasive.

So far, I've been using this in one personal project for almost 2
years now. It has not changed much in that time.

Having the Lamport clock, current thread name, context, and label
associated with each log entry has been immensely helpful.

Getting a stack dump for "free" just by including the Throwable
has saved me quite a bit of duplicated error-handling code (which I
always seem to manage to botch up with copy/paste errors).

Having the details available when I need them as a hashmap that I
can just edn/read is much more convenient than formatting them into a
message string.

Writing a Logger for a "real" logging library won't quite be trivial.
But it should be easy. And the fact that each log entry is structured
and includes the log level should make it worthwhile.

TODO item: add implementation samples.

I obviously think that it's promising enough to share.

### Downsides

#### Non-serializable objects

As soon as we start doing anything interesting with these log entries
(like sending them across the wire to a web browser), we need to
serialize them.

That doesn't work very well when you can put arbitrary data in the
:details field.

I definitely don't want to limit those fields to primitive values that
can be directly converted to json: it's useful in other instances to
have real objects available for inspection and interaction.

I need to put more thought into this.

#### Exceptions

When you throw one, you're very likely to get weird logging artifacts.

There's a good chance you'll have some sort of accumulated log-state
outside the (try) block, unless you `flush!` before entering.

If you don't `flush!` before throwing the exception, any log entries
accumulated between the outer `(try)` and your `(throw)` will be lost.

#### Duplicates

If you flush the same log-state twice, you'll obviously get
duplicate entries in the output.

One way for this happens is to start a `(try)` block
without calling `(flush!)`, call `(flush!)` just before throwing
an exception, and then flush the state from the outer logs in a
`(finally)` clause.

Other ways are usually variations of the same theme. I've run into
this both when:

* setting up a callback as a partial with some accumulated log state
* entering a loop with one that hasn't been freshly flushed.

#### Misleading Errors

If you mess up and try to write to a nil log-state, you'll get an
entry with  message about "Desperation warning: missing clock among
falsey log-state".

## Publishing to clojars

```bash
> boot to-clojars
```

## License

Copyright © 2018-2019 James Gatannah

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.
