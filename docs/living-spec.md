# VulnFlow — especificación viva

> **Borrador sin validar.** Lo generó un agente a partir del README, la documentación, el CHANGELOG y el código de la referencia indicada: describe lo que hace el código en esa referencia, no reglas confirmadas por el responsable del proyecto, que lo revisará la primera vez que se trabaje en cada área.

Referencia revisada: `origin/main` (`7224040`), 27 de octubre de 2026.

## Ingesta de informes

- La API de máquina (`/api/v1/**`) exige `X-API-Key`; la consola usa su propia sesión y nunca acepta esa clave. [Seguridad](security.md)
- `POST /api/v1/scans/trivy` registra el informe Trivy de un activo y responde sin esperar al procesamiento. Rechaza el fichero vacío, el que supera 10 MB y el que no declara `application/json` o un tipo `+json`; el contenido se valida después, y un informe inválido termina en fallo sin reintentos. [README](../README.md), [ADR-023](decisions/ADR-023-json-report-media-types.md)
- El mismo contenido (SHA-256) para el mismo activo no se procesa dos veces: responde `202` con `ACCEPTED`, `ALREADY_QUEUED` o `ALREADY_PROCESSING`, o `200` con `DUPLICATE` o `DEAD_LETTER`. En modo AWS la respuesta refleja el estado de publicación del evento, no el resultado final. [README](../README.md)
- `PUT /api/v1/assets/resolve` es idempotente por tipo y referencia externa: `201` si crea el activo y `200` con el existente, que conserva su nombre. `POST /api/v1/assets` con una identidad repetida responde `409`. [README](../README.md)

## Procesamiento

- El modo local es el predeterminado: un worker programado toma los trabajos de PostgreSQL, comprueba el SHA-256 antes de leer el informe y guarda en una sola transacción los hallazgos, el análisis y el trabajo completados. [Arquitectura](architecture.md)
- Solo se reintentan los fallos transitorios de almacenamiento o de base de datos (3 intentos; esperas de 5 s, 30 s y 2 min). Un informe inválido, ausente o alterado, o un error desconocido, deja el trabajo en `DEAD_LETTER` y el análisis en `FAILED`. El redrive manual solo acepta trabajos en `DEAD_LETTER` con el informe disponible (`202`); en otro caso responde `409`. [README](../README.md)
- En modo AWS (perfil `aws`, el de producción según el README), la API sube el informe a S3 y registra en una transacción el análisis y su evento en una outbox de PostgreSQL. La outbox publica en SQS con hasta 5 intentos y un administrador puede reintentar desde la consola las publicaciones fallidas. Una Lambda procesa el evento y guarda el resultado en DynamoDB. [Arquitectura AWS](aws-architecture.md)
- En modo AWS, DynamoDB manda sobre el estado y los hallazgos; PostgreSQL solo guarda identidad, deduplicación y publicación. Los fallos permanentes (informe inválido, ausente o alterado, o con más hallazgos que el límite, 100.000 por defecto) quedan como resultado `FAILED`; los transitorios vuelven a SQS. [ADR-021](decisions/ADR-021-local-and-aws-sources-of-truth.md)
- El riesgo de un hallazgo nuevo es la mayor puntuación CVSS válida del informe, escalada a 0–100; sin CVSS se usa la gravedad (`CRITICAL` 90, `HIGH` 70, `MEDIUM` 40, `LOW` 20, `UNKNOWN` 0). Los hallazgos ya guardados conservan su puntuación. [Calculadora de riesgo](../processing-core/src/main/java/com/vulnflow/processing/DefaultFindingRiskCalculator.java)

## Agente

- El agente es opcional y solo abre conexiones salientes. Escanea con Trivy las imágenes de su `targets.yml` (cada hora por defecto), guarda cada informe en una outbox local duradera antes de usar la red y lo sube por la API de máquina tras comprobar su SHA-256. [Agente](agent.md)
- Al subir, `200` o `202` marcan el informe como subido; `401` o `403` reintentan tras al menos una hora; un `404` del activo lo vuelve a resolver una vez; otro `4xx` lo deja en `DEAD_LETTER`, y `5xx` o un error de red reintentan con espera exponencial. Los informes pendientes, en reintento o en `DEAD_LETTER` nunca se borran solos. [Agente](agent.md)
- Con los comandos activados (`VULNFLOW_AGENT_COMMANDS_ENABLED`, desactivado por defecto), el agente confía en el catálogo del servidor: escanea la imagen asignada aunque no esté en `targets.yml`, que solo programa escaneos recurrentes. [ADR-024](decisions/ADR-024-api-owned-command-targets.md)
- Las imágenes privadas de GHCR se leen con una credencial `read:packages` propia del agente, montada en solo lectura; la consola no envía ni guarda credenciales de registro. [Escaneos bajo demanda](operations/on-demand-scans.md)

## Consola privada

- `/login` y `/app/**` forman una consola privada, desactivada por defecto (`VULNFLOW_UI_ENABLED`), con sesiones en PostgreSQL que caducan tras 8 horas de inactividad y protección CSRF. No hay registro público, recuperación por correo, subida de informes ni reprocesado de la DLQ de SQS. [Consola](ui/console.md)
- `OPERATOR` consulta el historial operativo y solicita escaneos de imágenes del catálogo; `ADMIN` además gestiona usuarios e imágenes, lee la auditoría y reintenta publicaciones fallidas. [Consola](ui/console.md)
- El primer administrador se crea desde variables de arranque solo si no existe ningún usuario. Las contraseñas temporales se muestran una vez y obligan a cambiarlas antes de seguir; la nueva debe tener de 14 a 128 caracteres con mayúsculas, minúsculas y números. Cinco fallos de acceso bloquean la cuenta 15 minutos. [Autenticación](../backend/src/main/java/com/vulnflow/ui/auth/UiAuthenticationService.java), [Usuario](../backend/src/main/java/com/vulnflow/ui/auth/UiUser.java)
- Desactivar una cuenta o rotar su contraseña revoca sus sesiones; quien cambia su propia contraseña conserva la sesión actual y pierde las demás. [Revocación de sesiones](security/session-revocation.md)

## Escaneos bajo demanda

- Están desactivados por defecto (`VULNFLOW_UI_SCANS_ENABLED`). Un administrador registra una referencia exacta de imagen, sin comodines, y el sistema resuelve y enlaza su activo; registrarla no programa ningún escaneo. Un usuario elige la imagen y, si quiere, el agente, y pulsa *Launch scan*; sin agente elegido se asigna uno disponible, y la asignación se mantiene si se recupera la reclamación. [Escaneos bajo demanda](operations/on-demand-scans.md)
- Límites: una solicitud activa por usuario, 5 por hora y 20 al día por usuario, cola de 25, 10 minutos entre solicitudes de la misma imagen, caducidad a los 30 minutos sin reclamar y 2 recuperaciones de reclamaciones abandonadas. Se rechaza si el agente está desconectado, sin disco suficiente o con la outbox en 1 GiB. La admisión se serializa para que los límites se cumplan con peticiones simultáneas. [Escaneos bajo demanda](operations/on-demand-scans.md), [Admisión](operations/console-admission.md)
- Un token de reclamación obsoleto no puede iniciar, renovar, subir, completar ni fallar la solicitud. El reintento de una subida ya aceptada se reconoce con el token original si coinciden solicitud, activo y SHA-256. [Reintento de subidas](decisions/ADR-024-command-upload-retry.md)
- Un proceso programado concilia cada 10 s hasta 25 solicitudes en proceso con su resultado local o de DynamoDB, sin depender del navegador. Los informes de comandos fallidos se conservan en el agente sin bloquear nuevas reclamaciones, y repetir la notificación de un fallo no cambia el resultado. [Conciliación](operations/console-reconciliation.md), [Notificaciones de fallo](operations/agent-failure-notifications.md)
- El operador solo ve sus propias solicitudes; el administrador ve todas. [Servicio de solicitudes](../backend/src/main/java/com/vulnflow/ui/scan/UiScanRequestService.java)

## Resultados y hallazgos

- *Results* lista todos los resultados ingeridos (escaneos programados, subidas directas y solicitudes de la consola), del más reciente al más antiguo, con filtro por imagen. [Consulta de la consola](../backend/src/main/java/com/vulnflow/ui/query/UiQueryController.java)
- *Findings* trabaja sobre un resultado: se elige imagen y resultado, se busca por CVE, paquete o título (hasta 100 caracteres) y se filtra por gravedad. Los hallazgos se ordenan por riesgo descendente en todas las páginas, con total exacto; en modo AWS se leen como máximo 100.000 hallazgos por resultado. Desde una solicitud, *Explore findings* abre su imagen y su resultado. [Consola](ui/console.md)
- El panel calcula la postura actual con el último resultado de cada imagen dentro de los 500 análisis más recientes de los últimos 30 días, para no contar dos veces los históricos. Cero hallazgos significa que el informe no trae hallazgos, no que la imagen sea segura. [Lectura de resultados](ui/console-results.md)
- En modo AWS, la consulta de un análisis recupera el resultado almacenado o, mientras se procesa, su proyección pendiente; un identificador desconocido se informa como no encontrado. Los hallazgos se consultan paginados por cursor y solo aparecen cuando el resultado está completado. [Consulta de resultados](../backend/src/main/java/com/vulnflow/aws/query/AwsResultQueryService.java)
- En modo local, la API de máquina lista hallazgos filtrando por gravedad, estado, activo y explotación conocida, y permite cambiar el estado (`OPEN`, `ACCEPTED`, `RESOLVED`, `FALSE_POSITIVE`); en modo AWS estos endpoints no existen. [Servicio de hallazgos](../backend/src/main/java/com/vulnflow/finding/FindingService.java)

## Página pública

- `/` es una página estática y saneada que no llama a ninguna API: muestra una ejecución real completada de `debian:11-slim` en AWS y una muestra buscable de sus 190 hallazgos, ordenada de `CRITICAL` a `UNKNOWN`. [Consola](ui/console.md)

## Sustituido

| Antes | Ahora | Cambio y fecha |
| --- | --- | --- |
| El historial de solicitudes de la consola no se limitaba por usuario. | El operador solo ve sus solicitudes; el administrador, todas. | 0.4.12, 30 de agosto de 2026 ([CHANGELOG](../CHANGELOG.md)) |
| Un escaneo remoto exigía que la imagen estuviera también en la lista local del agente. | Lo autoriza el catálogo de la API; `targets.yml` solo programa escaneos recurrentes. | 0.4.27, 16 de septiembre de 2026 ([ADR-024](decisions/ADR-024-api-owned-command-targets.md)) |
| *Findings* ofrecía las 100 primeras solicitudes completadas de la consola. | Se elige cualquier imagen y uno de sus resultados. | 0.4.28, 16 de septiembre de 2026 ([CHANGELOG](../CHANGELOG.md)) |
| La postura del panel sumaba todos los análisis recientes, también los históricos de una misma imagen. | Usa solo el último resultado de cada imagen. | 0.4.28, 16 de septiembre de 2026 ([CHANGELOG](../CHANGELOG.md)) |
| Los hallazgos no se ordenaban por riesgo entre páginas; en AWS se recorrían por cursor, sin total exacto. | Orden por riesgo descendente en todas las páginas; en AWS, paginación por número con total exacto. | Sin versión, 22 de septiembre de 2026 ([CHANGELOG](../CHANGELOG.md)) |
| El riesgo dependía solo de la gravedad. | Los hallazgos nuevos usan la mayor puntuación CVSS del informe. | Sin versión, 22 de septiembre de 2026 ([CHANGELOG](../CHANGELOG.md)) |
| La página pública reproducía una ejecución sintética. | Muestra una instantánea real de `debian:11-slim`. | Sin versión, 22 de septiembre de 2026 ([CHANGELOG](../CHANGELOG.md)) |

## Pendiente

- **En duda:** retención de los informes ya subidos por el agente. El [README](../README.md), [agent.md](agent.md), el [README del agente](../agent/README.md) y la [guía de escaneos bajo demanda](operations/on-demand-scans.md) dicen 24 horas, pero el [código](../agent/src/main/java/com/vulnflow/agent/config/AgentConfigLoader.java) usa 7 días por defecto; el [Compose de producción](../deploy/docker-compose.prod.yml) aplica 24 horas si no se define la variable y el [ejemplo de entorno](../deploy/.env.prod.example) la fija en 7 días. Decide el responsable qué valor rige.
- **En duda:** [agent.md](agent.md) y [security.md](security.md) dicen que el agente solo escanea las imágenes de su YAML, pero con los comandos activados el [código](../agent/src/main/java/com/vulnflow/agent/scheduling/CommandCoordinator.java) escanea las que asigna el catálogo de la API, como establece ADR-024. Lo cierra confirmar la regla y corregir esos documentos.
- **En duda:** [console-results.md](ui/console-results.md) aún dice que *Findings* ofrece las 100 primeras solicitudes completadas y que los resultados AWS se recorren por cursor; el [código](../backend/src/main/java/com/vulnflow/ui/query/UiQueryController.java) elige imagen y resultado y pagina por número. Lo cierra confirmar la regla y corregir el documento.
- **Hecho, falta subir:** en modo AWS, los cambios de `main` que ejecuta la Lambda (por ejemplo, el riesgo por CVSS o el límite de hallazgos como fallo permanente) solo rigen tras promoverla aparte, según la [entrega revisada](operations/reviewed-delivery.md); el repositorio no registra esa promoción ni qué revisión sirve producción. Lo desbloquea un despliegue autorizado por el responsable y su verificación.
