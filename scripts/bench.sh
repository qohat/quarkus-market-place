#!/usr/bin/env bash
#
# Banco de pruebas del módulo 4: mide los tres modelos de concurrencia bajo carga creciente.
#
#   ./scripts/bench.sh              escenario SINTÉTICO   (100 ms de I/O simulada, sin BD)
#   PREFIJO=/bench/db ./scripts/bench.sh   escenario con BASE DE DATOS REAL (20 conexiones)
#
# La gracia está en comparar las dos tablas: en la primera los hilos son el recurso escaso,
# en la segunda lo es el pool de conexiones. Si la hipótesis del módulo es cierta, la ventaja
# de virtual/reactivo se evapora en la segunda.
#
# Requisitos:
#   brew install hey
#   docker run -d --name mp-bench-db -e POSTGRES_USER=marketplace \
#     -e POSTGRES_PASSWORD=marketplace -e POSTGRES_DB=marketplace -p 55432:5432 postgres:18
#   ./gradlew quarkusBuild -Dquarkus.profile=bench
#   java -Dquarkus.profile=bench -jar build/quarkus-app/quarkus-run.jar
#
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PREFIJO="${PREFIJO:-/bench}"
DURACION="${DURACION:-12s}"
CONCURRENCIAS="${CONCURRENCIAS:-50 500 2000}"
MODELOS="${MODELOS:-blocking virtual reactive}"

# hey abre un descriptor por conexión. En macOS el límite por defecto (256) se queda corto en
# cuanto subes de concurrencia, y el síntoma son errores de socket que parecen del servidor
# pero son del cliente. Un benchmark mal instrumentado miente en la dirección más cómoda.
ulimit -n 65536 2>/dev/null || echo "aviso: no se pudo subir el límite de descriptores"

command -v hey >/dev/null || { echo "falta hey: brew install hey"; exit 1; }
curl -sf "${BASE_URL}${PREFIJO}/blocking" >/dev/null || {
    echo "no responde ${BASE_URL}${PREFIJO} — ¿arrancado con -Dquarkus.profile=bench?"
    exit 1
}

# Calentamiento: las primeras peticiones pagan la carga de clases y la compilación JIT.
# Medirlas mezcla el coste de arranque con el rendimiento en régimen permanente.
echo "calentando ${PREFIJO}..."
for m in ${MODELOS}; do hey -z 5s -c 50 "${BASE_URL}${PREFIJO}/${m}" >/dev/null 2>&1; done

printf '\n%-12s %-6s %12s %10s %10s %10s\n' MODELO CONC "REQ/S" "p50" "p99" "ERRORES"
printf '%s\n' "--------------------------------------------------------------------"

for c in ${CONCURRENCIAS}; do
    for m in ${MODELOS}; do
        salida=$(hey -z "${DURACION}" -c "${c}" "${BASE_URL}${PREFIJO}/${m}" 2>&1)

        rps=$(awk '/Requests\/sec:/ {printf "%.0f", $2}' <<<"${salida}")
        p50=$(awk '/^  50%/ {printf "%.0f", $3*1000}' <<<"${salida}")
        p99=$(awk '/^  99%/ {printf "%.0f", $3*1000}' <<<"${salida}")
        # Se cuentan tanto los códigos no-2xx como los fallos de socket: una petición que el
        # cliente no pudo ni enviar también es una petición perdida.
        err=$(awk '/Error distribution/,0 {if (/\[[0-9]+\]/) {gsub(/[^0-9]/," ",$0); s+=$1}} END {print s+0}' <<<"${salida}")

        printf '%-12s %-6s %12s %8sms %8sms %10s\n' "${m}" "${c}" "${rps:-?}" "${p50:-?}" "${p99:-?}" "${err}"
    done
    echo
done
