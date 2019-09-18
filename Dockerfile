FROM openjdk:8-jre

ONBUILD ADD application.conf /conf/application.conf

RUN mkdir /conf
ADD app.jar /app.jar

LABEL Application=@project.name@
LABEL Version=@project.version@
LABEL Maintainer="info@lemonade.be"
LABEL Commithash=@env.BITBUCKET_REPO_SLUG@
LABEL RepoURL=@env.BITBUCKET_COMMIT@

ENTRYPOINT [ "java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app.jar", "9000"]
