ARG BASE_VERSION

FROM adoptopenjdk/openjdk11:alpine
ADD src ./src/
ADD gradle ./gradle/
ADD build.gradle ./build.gradle
ADD gradlew ./gradlew
ADD settings.gradle ./settings.gradle
RUN ./gradlew clean jar

FROM emcmongoose/mongoose-base:${BASE_VERSION}
ARG BASE_VERSION
ARG VERSION
RUN mkdir -p $HOME/.mongoose/${BASE_VERSION}/ext
COPY --from=0 /build/libs/mongoose-storage-driver-preempt-${VERSION}.jar /tmp/
ADD ci/docker/entrypoint_storage_driver_preempt.sh /opt/mongoose/entrypoint_storage_driver_preempt.sh
RUN mv -f /tmp/mongoose-storage-driver-preempt-*.jar $HOME/.mongoose/${BASE_VERSION}/ext/; \
    chmod +x /opt/mongoose/entrypoint_storage_driver_preempt.sh
ENTRYPOINT ["/opt/mongoose/entrypoint_storage_driver_preempt.sh"]
