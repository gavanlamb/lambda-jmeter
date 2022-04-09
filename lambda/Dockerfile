FROM maven:3.8.5-jdk-8 as build
ARG BUILD_NUMBER=1.0.0-1
WORKDIR /var/app
COPY . .
RUN mvn versions:set -DnewVersion=${BUILD_NUMBER}
RUN mvn -B -e -C install
RUN mvn -B -e -C compile

FROM amazon/aws-lambda-java:8 as lambda-jmeter
COPY --from=build /var/app/target/classes/ ${LAMBDA_TASK_ROOT}
COPY --from=build /var/app/target/lib ${LAMBDA_TASK_ROOT}/lib/
COPY --from=build /var/app/target/dependency ${LAMBDA_TASK_ROOT}/lib/
RUN chmod -R 777 ${LAMBDA_TASK_ROOT}/lib/
ENV JAVA_HOME=/home/jmeter
RUN mkdir -p /home/jmeter/.java/.systemPrefs
RUN touch /home/jmeter/.java/.systemPrefs/tmp.txt
RUN chmod -R 777 /home/jmeter

CMD [ "JMeter.App::handleRequest" ]
