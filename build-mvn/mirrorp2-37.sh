#!/bin/sh
DIR=`pwd`
echo "dir $DIR"
echo $0
fetch()
{
  COMMAND=$@
  $ECLIPSE -nosplash -verbose  -application org.eclipse.equinox.p2.artifact.repository.mirrorApplication $COMMAND
  $ECLIPSE -nosplash -verbose  -application org.eclipse.equinox.p2.metadata.repository.mirrorApplication $COMMAND
}

echo "scala tools"
fetch -source http://download.scala-ide.org/scala-eclipse-toolchain-osgi-2.9.1.final -destination file:$DIR/scalaToolsMirror-2.9.1/

echo "3.7 updates"
fetch -source http://download.eclipse.org/eclipse/updates/3.7/ -destination file:$DIR/3.7UpdatesMirror/ 

echo "indigo"
fetch -source http://download.eclipse.org/releases/indigo/ -destination file:$DIR/indigoMirror/ 

echo "gef"
fetch -source http://download.eclipse.org/tools/gef/updates/releases/ -destination file:$DIR/gefMirror/ 


