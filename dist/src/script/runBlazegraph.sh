#!/usr/bin/env bash
set -e

BLAZEGRAPH_CONFIG=${BLAZEGRAPH_CONFIG:-"/etc/default/wdqs-blazegraph"}
if [ -r $BLAZEGRAPH_CONFIG ]; then
  . $BLAZEGRAPH_CONFIG
fi

HOST=${HOST:-"localhost"}
CONTEXT=bigdata
PORT=${PORT:-"9999"}
DIR=${DIR:-`dirname $0`}
PREFIXES_FILE=$DIR/prefixes.conf
WIKIBASE_CONCEPT_URI_PARAM=${WIKIBASE_CONCEPT_URI_PARAM:-""}
COMMONS_CONCEPT_URI_PARAM=${COMMONS_CONCEPT_URI_PARAM:-""}
HEAP_SIZE=${HEAP_SIZE:-"16g"}
LOG_CONFIG=${LOG_CONFIG:-""}
LOG_DIR=${LOG_DIR:-"/var/log/wdqs"}
GC_LOG_FILE=${GC_LOG_FILE:-"wdqs-blazegraph_jvm_gc.%p-%t.log"}
MEMORY=${MEMORY:-"-Xmx${HEAP_SIZE}"}
JAVA_MAJOR_VERSION=$(java -version 2>&1 | sed -E -n 's/.* version "([0-9]*).*$/\1/p')
if [[ "$JAVA_MAJOR_VERSION" -ge "9" ]] ; then
    GC_LOGS=${GC_LOGS:-"-Xlog:gc:${LOG_DIR}/${GC_LOG_FILE} \
         -Xlog:gc* \
         -XX:+UnlockExperimentalVMOptions \
         -XX:G1NewSizePercent=20 \
         -XX:+ParallelRefProcEnabled"}
else
    GC_LOGS=${GC_LOGS:-"-Xloggc:${LOG_DIR}/${GC_LOG_FILE} \
         -XX:+PrintGCDetails \
         -XX:+PrintGCDateStamps \
         -XX:+PrintGCTimeStamps \
         -XX:+PrintAdaptiveSizePolicy \
         -XX:+PrintReferenceGC \
         -XX:+PrintGCCause \
         -XX:+PrintGCApplicationStoppedTime \
         -XX:+PrintTenuringDistribution \
         -XX:+UnlockExperimentalVMOptions \
         -XX:G1NewSizePercent=20 \
         -XX:+ParallelRefProcEnabled \
         -XX:+UseGCLogFileRotation \
         -XX:NumberOfGCLogFiles=10 \
         -XX:GCLogFileSize=20M"}
fi
EXTRA_JVM_OPTS=${EXTRA_JVM_OPTS:-""}
BLAZEGRAPH_OPTS=${BLAZEGRAPH_OPTS:-""}
CONFIG_FILE=${CONFIG_FILE:-"RWStore.properties"}
DEBUG=-agentlib:jdwp=transport=dt_socket,server=y,address=8000,suspend=n
# Disable this for debugging
DEBUG=

function usage() {
  echo "Usage: $0 [-h <host>] [-d <dir>] [-c <context>] [-p <port>] " \
  "[-o <blazegraph options>] [-f config.properties] [-n prefixes.conf] [-w wikibaseConceptUri] [-m commonsConceptUri]"
  exit 1
}

while getopts h:c:p:d:o:f:n:w:m:? option
do
  case "${option}"
  in
    h) HOST=${OPTARG};;
    c) CONTEXT=${OPTARG};;
    p) PORT=${OPTARG};;
    d) DIR=${OPTARG};;
    o) BLAZEGRAPH_OPTS="${OPTARG}";;
    f) CONFIG_FILE=${OPTARG};;
    n) PREFIXES_FILE=${OPTARG};;
    w) WIKIBASE_CONCEPT_URI_PARAM="-DwikibaseConceptUri=${OPTARG} ";;
    m) COMMONS_CONCEPT_URI_PARAM="-DcommonsConceptUri=${OPTARG} ";;
    ?) usage;;
  esac
done

pushd $DIR

# Q-id of the default globe
DEFAULT_GLOBE=${DEFAULT_GLOBE:-"2"}
# Blazegraph HTTP User Agent for federation
USER_AGENT=${USER_AGENT:-"Wikidata Query Service; https://query.wikidata.org/"}

LOG_OPTIONS=""
if [ ! -z "$LOG_CONFIG" ]; then
    LOG_OPTIONS="-Dlogback.configurationFile=${LOG_CONFIG}"
fi

echo "Running Blazegraph from `pwd` on :$PORT/$CONTEXT"
exec java \
     -server -XX:+UseG1GC ${MEMORY} ${DEBUG} ${GC_LOGS} ${LOG_OPTIONS} ${EXTRA_JVM_OPTS} \
     -Dcom.bigdata.rdf.sail.webapp.ConfigParams.propertyFile="${CONFIG_FILE}" \
     -Dorg.eclipse.jetty.server.Request.maxFormContentSize=200000000 \
     -Dcom.bigdata.rdf.sparql.ast.QueryHints.analytic=true \
     -Dcom.bigdata.rdf.sparql.ast.QueryHints.analyticMaxMemoryPerQuery=1073741824 \
     -DASTOptimizerClass=org.wikidata.query.rdf.blazegraph.WikibaseOptimizers \
     -Dorg.wikidata.query.rdf.blazegraph.inline.literal.WKTSerializer.noGlobe=$DEFAULT_GLOBE \
     -Dcom.bigdata.rdf.sail.webapp.client.RemoteRepository.maxRequestURLLength=7168 \
     -Dcom.bigdata.rdf.sail.sparql.PrefixDeclProcessor.additionalDeclsFile="$PREFIXES_FILE" \
     -Dorg.wikidata.query.rdf.blazegraph.mwapi.MWApiServiceFactory.config=$DIR/mwservices.json \
     -Dcom.bigdata.rdf.sail.webapp.client.HttpClientConfigurator=org.wikidata.query.rdf.blazegraph.ProxiedHttpConnectionFactory \
     -Dhttp.userAgent="${USER_AGENT}" \
     $WIKIBASE_CONCEPT_URI_PARAM \
     $COMMONS_CONCEPT_URI_PARAM \
     -Dorg.eclipse.jetty.annotations.AnnotationParser.LEVEL=OFF \
     ${BLAZEGRAPH_OPTS} \
     -jar jetty-runner*.jar \
     --host $HOST \
     --port $PORT \
     --path /$CONTEXT \
     blazegraph-service-*.war
