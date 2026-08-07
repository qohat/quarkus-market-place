#!/usr/bin/env bash
#
# Muestra qué clases generó Quarkus en build time (augmentation).
#
# Quarkus no lleva tu código tal cual al artefacto: durante el build, ARC genera las clases
# de inyección (*_Bean, *_ClientProxy, *_Subclass) y Quarkus REST genera un invoker por
# endpoint. Todo eso acaba en un jar aparte, generated-bytecode.jar, que este script inspecciona.
#
# Detalle importante: ARC ELIMINA los beans que nadie inyecta. Si una de tus clases no aparece
# aquí, es que ARC la consideró código muerto y la borró del artefacto de producción.
#
# Uso:  ./scripts/inspeccionar-arc.sh [patron]
#       ./scripts/inspeccionar-arc.sh              -> todo tu código (com/marketplace y org/acme)
#       ./scripts/inspeccionar-arc.sh Listing      -> solo lo que contenga "Listing"

set -euo pipefail

cd "$(dirname "$0")/.."

JAR="build/quarkus-app/quarkus/generated-bytecode.jar"
PATRON="${1:-}"

if [[ ! -f "$JAR" ]]; then
    echo "No existe $JAR"
    echo "Ejecuta primero:  ./gradlew quarkusBuild"
    exit 1
fi

echo "Artefacto: $JAR"
echo "Generado:  $(date -r "$JAR" '+%Y-%m-%d %H:%M:%S')"
echo

listar() {
    unzip -l "$JAR" 2>/dev/null | awk '{print $NF}' | grep "\.class$" | grep -E "$1" || true
}

if [[ -n "$PATRON" ]]; then
    echo "== Clases generadas que contienen '$PATRON' =="
    resultado=$(listar "$PATRON")
else
    echo "== Clases generadas a partir de TU código =="
    resultado=$(listar '^(com/marketplace|org/acme)/')
fi

if [[ -z "$resultado" ]]; then
    echo "  (ninguna)"
    echo
    echo "  Si esperabas ver algo aquí, lo más probable es que ARC haya eliminado el bean"
    echo "  por no estar inyectado en ningún sitio del código de PRODUCCIÓN. Que lo usen los"
    echo "  tests no cuenta: en el build de test Quarkus incluye las clases de test como"
    echo "  consumidoras, pero en el de producción no existen."
    echo
    echo "  Soluciones: inyectarlo desde algo alcanzable (un @Path, por ejemplo) o marcarlo"
    echo "  con @Unremovable si se resuelve de forma dinámica."
else
    echo "$resultado" | sed 's/^/  /'
fi

echo
echo "== Qué significa cada sufijo =="
cat <<'EOF'
  *_Bean            fábrica del bean: ARC generó el código que lo construye y le
                    inyecta sus dependencias. Sin reflection.
  *_ClientProxy     proxy de scope. Es lo que recibes al hacer @Inject de un
                    @ApplicationScoped; en cada llamada resuelve la instancia real.
  *_Subclass        subclase con interceptores (@Transactional, @CacheResult...).
  *quarkusrestinvoker*
                    invoker de un endpoint REST. Llama a tu método con invokevirtual
                    directo, en vez de Method.invoke() por reflection.
EOF
