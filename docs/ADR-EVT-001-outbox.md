# HU-EVT-001 - Outbox transaccional, primera entrega

La decision acompana al cambio: un trigger AFTER INSERT de inventory.movement crea
un evento inventory.stock.changed v1 en inventory.outbox, en la misma transaccion.
Si falla cualquiera de las escrituras, saldo, movimiento, intencion y evento se revierten.
No hay RPC en la transaccion ni escrituras entre esquemas. El trigger es SECURITY INVOKER
con search_path fijo y referencias calificadas; inventory_app solo usa privilegios propios.

eventId es movementId: un replay idempotente de entrada/salida no inserta otro movimiento
ni otro evento. sequence es bigint creciente de una secuencia PostgreSQL: puede tener
huecos tras rollback y NO representa orden global de commit. Por lote, las APIs serializan
el cambio de saldo antes del movimiento; el evento captura onHandQuantity del lote.
El futuro consumidor debe tolerar redelivery y eventos fuera de orden con esta revision.

Payload: IDs UUID, tipo ENTRY/EXIT, cantidad positiva, delta firmado, saldo fisico,
vencimiento y traza original validada. No incluye passwords, tokens, motivo de salida
ni nombres de personas. onHandQuantity no equivale a disponibilidad de un lote vencido.
Las APIs pasan trace_id al insertar movimiento. Un cliente anterior durante despliegue
gradual usa movementId como traza de respaldo; no se inventa una traza HTTP historica.

Solo movimientos nuevos emiten eventos; no hay backfill de movimientos historicos ni
de saldos iniciales. Al habilitar consumidores, planear bootstrap desde la API de stock.
Preparar/cancelar una entrada, rechazos y replays sin movimiento no crean eventos.
La migracion agrega objetos, no modifica SQL aplicado ni movimientos existentes.
Rollback de la nueva migracion solo si outbox esta vacio: nunca borrar pendientes para
forzarlo. El rol de API no puede editar payload/identidad ni eliminar eventos.

Esta entrega cubre persistencia atomica y supervivencia al reinicio. Todos los eventos
quedan PENDING: no hay relay, transporte, ACK, reintentos de entrega ni consumidor.
La segunda entrega debe definir transporte/identidad, publicar con reintentos acotados,
probar perdida de ACK, lease/reinicio y deduplicacion atomica del efecto. No cerrar
HU-EVT-001 ni afirmar entrega exactamente una vez con solo esta tabla.

Referencias: [PostgreSQL, transacciones de triggers](https://www.postgresql.org/docs/16/trigger-definition.html)
y [Transactional Outbox](https://microservices.io/patterns/data/transactional-outbox.html).
