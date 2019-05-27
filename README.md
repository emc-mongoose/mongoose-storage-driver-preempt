[![Gitter chat](https://badges.gitter.im/emc-mongoose.png)](https://gitter.im/emc-mongoose)
[![Issue Tracker](https://img.shields.io/badge/Issue-Tracker-red.svg)](https://mongoose-issues.atlassian.net/projects/GOOSE)
[![CI status](https://gitlab.com/emc-mongoose/mongoose-storage-driver-preempt/badges/master/pipeline.svg)](https://gitlab.com/emc-mongoose/mongoose-storage-driver-preempt/commits/master)
[![Tag](https://img.shields.io/github/tag/emc-mongoose/mongoose-storage-driver-preempt.svg)](https://github.com/emc-mongoose/mongoose-storage-driver-preempt/tags)
[![Maven metadata URL](https://img.shields.io/maven-metadata/v/http/central.maven.org/maven2/com/github/emc-mongoose/mongoose-storage-driver-preempt/maven-metadata.xml.svg)](http://central.maven.org/maven2/com/github/emc-mongoose/mongoose-storage-driver-preempt)
[![Sonatype Nexus (Releases)](https://img.shields.io/nexus/r/http/oss.sonatype.org/com.github.emc-mongoose/mongoose-storage-driver-preempt.svg)](http://oss.sonatype.org/com.github.emc-mongoose/mongoose-storage-driver-preempt)
[![Docker Pulls](https://img.shields.io/docker/pulls/emcmongoose/mongoose-storage-driver-preempt.svg)](https://hub.docker.com/r/emcmongoose/mongoose-storage-driver-preempt/)

# Contents

Preemptive multitasking (native threads pool) based storage driver. Uses thread-per-task approach for I/O.

# Design

## Batch Mode

A storage driver implementation can use the batch mode to work with the load operations to increase the efficiency.

Assuming
* Q<sub>output</sub>: `storage-driver-limit-queue-output`
* Q<sub>input</sub>: `storage-driver-limit-queue-input`
* S<sub>batch</sub>: `load-batch-size`

the following equation should always be true:
Q<sub>output</sub> &ge; Q<sub>input</sub> * S<sub>batch</sub>

otherwise, there may be the load operation results handling failures.

## Heap Memory Consumption

The default Mongoose's `load-batch-size` configuration value is 32,768. The preemptive
storage driver plugin which the task for each load operations batch. The default input queue size
(`storage-driver-limit-queue-input`) is 1,000,000. This yields the 32,768,000,000 instances of the load operations in
the runtime and require ~ 6 terabytes of the heap memory. To avoid this behavior, override the defaults:

Q<sub>input</sub> * S<sub>batch</sub> &le; 1,000,000
