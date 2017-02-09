# onyx-jepsen

Jepsen testing Onyx. **Work in progress**

We wrote a blog post describing our experience using Jepsen: [Onyx Straps in For a Jepsening ](http://www.onyxplatform.org/jekyll/update/2016/03/15/Onyx-Straps-In-For-A-Jepsening.html)

## Usage

To run:

1. Set onyx dependency versions for the peers in project.clj.
   Snapshot versions are acceptable, but be sure to lein install them before
   running your tests as you may end up downloading a snapshot jar from
   clojars.

2. If not using Linux, install [Docker Machine](https://docs.docker.com/machine/).

Then create a new "machine":

#### VMware Fusion instructions

Tune disk size, memory size and cpu counts to taste.

```
docker-machine create --driver vmwarefusion --vmwarefusion-disk-size 50000 --vmwarefusion-memory-size 20000 --vmwarefusion-cpu-count "6" jepsen-onyx
```

#### VirtualBox instructions:
```
docker-machine create --driver virtualbox --virtualbox-disk-size 50000 --virtualbox-memory 20000 --virtualbox-cpu-count 4  jepsen-onyx
```

3. Set docker-machine env:
```
eval "$(docker-machine env jepsen-onyx)"
```

4. Uberjar peers and start docker in docker instance:
```
script/start-containers.sh
```

5. Run from inside docker in docker.
```
script/run-test-start-in-docker.sh TEST_NS
```

Where TEST_NS is `onyx-jepsen.bookkeeper-test`.

When running a new test, exit the docker instance, and restart the process from
4. The docker containers have everything setup perfectly so that nothing needs
to be downloaded or installed before running a test. The jepsen test does not
clean up after itself so a new container must be started before running a new test.

## Docker Image

onyx-jepsen uses a custom jepsen docker image built specifically to test Onyx.
This includes pre-installed ZooKeeper. See the README in the docker directory
for more details.

## License

Copyright © 2015 Distributed Masonry LLC

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
