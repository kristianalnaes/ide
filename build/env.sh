#!/bin/sh

GIT_HASH="`git log -1 --pretty=format:"%h"`"
# GIT_BRANCH="`git branch --no-color 2> /dev/null | sed -e '/^[^*]/d' -e 's/* \(.*\)/\1/'`"

echo "git hash:" $GIT_HASH


# MAVEN needs to point to a MAVEN3 installation:
if which mvn >/dev/null; then
  mvn -version | grep "Maven 3" > /dev/null
  if [ $? -eq 0 ]; then
    MAVEN="mvn"
  fi
fi

if [ "X$MAVEN" = "X" ] ; then
  echo "Missing environment variable \"MAVEN\". This has to point to a maven 3.0 installation, "
  echo "e.g. add the following line to your .bashrc (and make sure the path is correct):"
  echo "export MAVEN=/opt/apache-maven-3.0-beta-1/bin/mvn"
  exit
fi

build()
{
  ${MAVEN} \
    $PROFILE_NAME -U \
    -Dscala.version=${SCALA_VERSION} \
    -Dgit.hash=${GIT_HASH} \
    -Dversion.tag=${VERSION_TAG}\
    $*
}
