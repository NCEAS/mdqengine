#!/bin/bash

#mvn exec:java -Dexec.mainClass="edu.ucsb.nceas.metadig.Worker" -Dexec.args="hi there"

java -cp ./target/metadig-engine-2.0.0-RC1.jar:./target/classes/solr edu.ucsb.nceas.mdqengine.Worker
