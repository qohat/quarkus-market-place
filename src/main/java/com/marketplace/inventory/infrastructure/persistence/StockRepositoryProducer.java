package com.marketplace.inventory.infrastructure.persistence;

import com.marketplace.inventory.domain.StockRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Decide qué estrategia de reserva usa la aplicación.
 *
 * <h2>Por qué un productor y no tres beans compitiendo</h2>
 *
 * Si las tres implementaciones se ofrecieran como {@link StockRepository}, ARC fallaría el build
 * con una dependencia ambigua: hay tres candidatos y ninguna forma de elegir. Marcarlas con
 * {@code @Typed} las deja inyectables solo por su clase concreta, y este método produce el único
 * bean que responde a la interfaz.
 *
 * <p>Podría haberse resuelto con {@code @IfBuildProperty}, que descarta beans en tiempo de
 * compilación —más eficiente, y lo natural en Quarkus—. Se ha elegido un productor porque
 * mantiene <strong>las tres implementaciones vivas a la vez</strong>, que es justo lo que permite
 * a {@code StockConcurrencyTest} ejecutar la misma batería contra las tres en una sola pasada. Sin
 * eso, comparar estrategias exigiría tres compilaciones y la comparación perdería valor.
 *
 * <p>En una aplicación real, con la estrategia ya decidida, {@code @IfBuildProperty} sería la
 * elección correcta: las otras dos ni siquiera acabarían en el artefacto.
 *
 * <h2>Por qué el valor por defecto es la atómica</h2>
 *
 * Porque es la que no se degrada bajo contención, que es exactamente cuando importa: durante un
 * flash sale. Las otras dos rinden bien mientras nadie compita, es decir, mientras da igual cuál
 * elijas.
 */
@ApplicationScoped
public class StockRepositoryProducer {

    /**
     * Propiedad de runtime: se puede cambiar con una variable de entorno al desplegar, sin
     * reconstruir. Útil para reaccionar en caliente si una estrategia da problemas en producción.
     */
    @ConfigProperty(name = "marketplace.inventory.strategy", defaultValue = "atomic")
    String strategy;

    @Produces
    @ApplicationScoped
    public StockRepository stockRepository(
            AtomicStockRepository atomic,
            OptimisticStockRepository optimistic,
            PessimisticStockRepository pessimistic) {

        return switch (strategy) {
            case "atomic" -> atomic;
            case "optimistic" -> optimistic;
            case "pessimistic" -> pessimistic;
            // Un valor mal escrito debe romper el arranque, no elegir en silencio una estrategia
            // que nadie pidió. Fallar aquí cuesta un reinicio; fallar en producción, ventas.
            default -> throw new IllegalStateException(
                    "Unknown marketplace.inventory.strategy: '" + strategy
                            + "'. Valid values: atomic, optimistic, pessimistic");
        };
    }
}
