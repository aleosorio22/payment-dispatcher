Nombre: René Alejandro Osorio González

Carnet: 0905-23-10736

Repositorio: https://github.com/aleosorio22/payment-dispatcher

**Diagrama:** [Arquitectura de la solución en Lucidchart](https://lucid.app/lucidchart/c04b6862-0b0b-4b14-aedb-03abb421da4a/view)

Modulo asignado: Envío de pagos a sistemas externos por colas

---

# Despachador de pagos

Cuando el cajero de la Oficina del Agua recibe Q50.40 de un vecino, ese cobro tiene que
llegar al banco, al ERP municipal y a la SAT. Este repositorio contiene los servicios que
se encargan de eso sin que el cajero espere, sin cobrar dos veces y sin perder un pago
cuando alguno de los tres destinos está caído.

Son tres servicios Java independientes más una función Lambda, desplegados en una EC2 y
comunicados por colas SQS.

## Alcance de este documento

La solución integrada de Fase 2 contempla cuatro features repartidas entre los integrantes.
Este repositorio contiene únicamente las que desarrollé:

- **Envío de pagos a sistemas externos por colas** — el módulo asignado, y el centro de
  este documento.
- **Replicación asíncrona** — una réplica MySQL del monolito con binlog y GTID, más la
  proyección del estado de cada envío en DynamoDB.

Las otras features (consulta pública con captcha, chatbot con RAG) fueron desarrolladas
por otros integrantes y viven en sus propios repositorios; no las documento aquí porque no
puedo verificar su código.

El monolito es un sistema Laravel propio y vive en un repositorio aparte:
[aleosorio22/gestion-agua-potable](https://github.com/aleosorio22/gestion-agua-potable).

---

## Arquitectura

**Diagrama:** [Arquitectura de la solución en Lucidchart](https://lucid.app/lucidchart/c04b6862-0b0b-4b14-aedb-03abb421da4a/view)

### Componentes reales

| Componente | Qué es | Dónde vive |
|---|---|---|
| Monolito | Laravel 13 + Filament 5 · padrón, lecturas, boletas y caja | EC2 propia, nginx + php-fpm |
| MySQL primario | La base del monolito | la misma EC2 |
| MySQL réplica | Copia de solo lectura, binlog con GTID | EC2 aparte |
| `producer` | Reclama los pagos cobrados y los publica en las colas | EC2 del despachador, `:8081` |
| `consumer` | Entrega cada pago al banco y al ERP | EC2 del despachador, `:8082` |
| `facturador` | Emite la factura electrónica del pago | EC2 del despachador, `:8083` |
| `pagos-envio` | Cola de los pagos por entregar, con su DLQ | SQS, `us-east-2` |
| `facturas-emitir` | Cola de los pagos por facturar, con su DLQ | SQS, `us-east-2` |
| `pago_envio_log` | Bitácora: un renglón por intento, con TTL | DynamoDB |
| `pago_envio_estado` | Resultado por destino, con stream activado | DynamoDB |
| `factura_emitida` | La autorización de la SAT por pago | DynamoDB |
| `agua-avisar-estado` | Avisa al monolito cómo terminó cada envío | Lambda, Node 22 |
| API Banco | Sistema externo del curso | fuera de nuestra infraestructura |
| API ERP municipal | Sistema externo del curso | fuera de nuestra infraestructura |
| DigiFact | Certificador FEL autorizado por la SAT | servicio externo |

### El recorrido de un pago

Los pasos van en orden; cada uno ocurre sin que nadie lo empuje a mano.

1. **El cajero registra el pago en Filament.** El monolito lo guarda con su correlativo y
   deja una fila en `pago_envios` en estado `PENDIENTE`. El cajero ya terminó: nada de lo
   que sigue lo hace esperar.
2. **El `producer` pregunta cada 30 segundos** si hay pagos pendientes, en lotes de hasta
   50. El monolito se los entrega bajo candado (`lockForUpdate`) y los marca `ENCOLADO`,
   para que dos ejecuciones del producer no reclamen el mismo pago.
3. **El `producer` publica el mismo pago en las dos colas.** Aquí se bifurca: una copia va
   a `pagos-envio` y otra a `facturas-emitir`, ambas con la misma llave.
4. **El `consumer` toma el mensaje** de `pagos-envio` y lo entrega al banco y al ERP, cada
   uno con su `Idempotency-Key`. Clasifica cada respuesta y escribe el resultado en
   DynamoDB.
5. **El stream de `pago_envio_estado` dispara la Lambda**, que llama al monolito para
   decirle cómo terminó. El monolito pasa el pago a `ENVIADO` o a `ERROR`.
6. **El `facturador` toma el mensaje** de `facturas-emitir`, arma el documento fiscal con
   el IVA desglosado y lo certifica en DigiFact. Guarda la autorización en
   `factura_emitida`; DigiFact le envía el PDF al vecino por correo.

### Cómo se comunica cada parte

| Conexión | Protocolo | Qué representa |
|---|---|---|
| `producer` → monolito | HTTPS, token de equipo | pide los pagos cobrados |
| `producer` → SQS | `SendMessage`, rol IAM | publica el pago en las dos colas |
| SQS → `consumer` / `facturador` | `ReceiveMessage`, espera larga | el servicio pregunta; SQS no empuja |
| `consumer` → banco y ERP | HTTPS, `Idempotency-Key` | entrega el pago |
| `consumer` → DynamoDB | SDK, rol IAM | bitácora y resultado |
| `pago_envio_estado` → Lambda | DynamoDB Stream | el cambio de la fila dispara el aviso |
| Lambda → monolito | HTTPS, token de equipo | cierra el ciclo |
| `facturador` → DigiFact | HTTPS, token mensual | certifica la factura |

Ninguno de los servicios Java se conoce entre sí. El `consumer` no sabe que el
`facturador` existe, y viceversa. Lo único que comparten es el formato del mensaje, que
vive en el módulo `common`.

---

## El módulo: envío de pagos por colas

### Funcionalidad y objetivo

El problema del negocio es concreto. La Oficina del Agua cobra en ventanilla, pero el banco
y el ERP municipal necesitan enterarse de cada cobro, y esos dos sistemas no siempre están
disponibles. Tres cosas no pueden pasar:

- **Que el cajero espere.** Si el cobro dependiera de que el banco conteste, una caída del
  banco cerraría la ventanilla.
- **Que un pago se cobre dos veces.** Si el sistema reintenta un envío cuya respuesta se
  perdió, el banco no puede registrar el pago otra vez.
- **Que un pago se pierda en silencio.** Un pago que nadie pudo entregar tiene que quedar
  a la vista de alguien.

La solución desacopla el cobro de la entrega. El monolito solo anota; una cola guarda el
trabajo pendiente, y los servicios lo van sacando a su ritmo. Si el banco está caído, los
pagos se acumulan en la cola y salen solos cuando vuelve.

### Integración y responsabilidades

**El monolito conserva su lógica de cobro intacta.** No se modificó el flujo de caja, el
cálculo de boletas ni el cierre de períodos. Lo que se le agregó es una API de integración
con dos operaciones y una tabla nueva (`pago_envios`) que registra el estado del envío:

```
POST  /api/integracion/pagos/reclamar          → entrega los pagos pendientes
PATCH /api/integracion/pagos/{llave}/envio     → recibe el resultado de cada destino
```

Las fronteras quedaron así, y cada una por una razón:

- **Solo el `producer` habla con el monolito.** El `consumer` y el `facturador` no tienen
  su URL ni su token. Si mañana el monolito cambia de dirección, hay un solo lugar que
  tocar.
- **El `consumer` no llama al monolito para avisarle del resultado.** Escribe en DynamoDB y
  la Lambda se encarga. La razón está en la sección siguiente.
- **Los secretos no están en el repositorio ni dentro del jar.** Viajan por variables de
  entorno de systemd. Para hablar con SQS y DynamoDB no hay credenciales: la EC2 lleva un
  rol IAM y el SDK pide credenciales temporales a la propia instancia.
- **Los servicios escuchan solo en `127.0.0.1`.** El endpoint de salud no tiene por qué
  verse desde internet.

### Decisiones técnicas

#### 1. El aviso al monolito pasa por un stream, no por una llamada directa

**La alternativa descartada:** que el `consumer`, al terminar de entregar un pago, llamara
al monolito para avisarle.

**Por qué no:** esa llamada puede fallar. Si el monolito está reiniciándose en ese
instante, el aviso se pierde y el pago queda marcado `ENCOLADO` para siempre, aunque el
banco ya lo tenga. El consumer tendría que reintentar el aviso, y reintentar significa
guardar el pendiente en algún lado — que es exactamente el problema que las colas ya
resuelven.

**Lo que se hizo:** el `consumer` escribe el resultado en `pago_envio_estado` y ahí termina
su trabajo. Esa tabla tiene un DynamoDB Stream que dispara la Lambda `agua-avisar-estado`,
y es la Lambda la que llama al monolito. Si la llamada falla, DynamoDB le vuelve a entregar
el mismo lote hasta que la acepte.

**Lo que costó:** una pieza más y un recorrido menos obvio de leer. Se ensayó apagando el
monolito cinco minutos con envíos en curso: no se perdió un solo aviso.

#### 2. Un 409 por duplicado se cuenta como entrega exitosa

Cuando el banco recibe el pago pero la respuesta se pierde en el camino, el reintento llega
con la misma `Idempotency-Key` y el banco contesta `409 DUPLICATE_PAYMENT`. Tratar ese 409
como error significaría reintentar para siempre un pago que ya está cobrado.

Toda la lectura de respuestas vive en una sola clase, `Clasificador`, compartida por los
dos destinos:

| Respuesta | Conclusión |
|---|---|
| `2xx` | entregado |
| `409` con código de duplicado | entregado |
| `409` por otro motivo | rechazo definitivo |
| `429` o `5xx` | reintentable |
| sin respuesta (timeout) | reintentable |
| `400`, `403`, `404`, `422` | rechazo definitivo |

La llave de idempotencia no se genera al vuelo: sale del correlativo del pago
(`EQD-000218`), así que dos intentos del mismo cobro no pueden inventarse dos llaves
distintas. La misma llave identifica el pago en el monolito, en el mensaje, en las tres
tablas y ante los dos destinos.

#### 3. Dos niveles de reintento, con propósitos distintos

Los **tres reintentos en memoria**, con espera creciente, cubren el tropiezo de un segundo:
un timeout, un 503 pasajero. Si se agotan, el mensaje **vuelve a la cola** y se reintenta
más tarde; eso cubre la caída de media hora, porque el mensaje sobrevive aunque el servicio
se reinicie.

A la quinta recepción, el `consumer` mueve el pago a la cola de descarte con el motivo
pegado como atributo. La cola SQS tiene configurado un sexto reintento como red de
seguridad, por si el servicio muriera justo en ese momento.

#### 4. La facturación es un servicio aparte, no un tercer destino del consumer

Entregar un pago al banco y facturarlo son dos obligaciones distintas que fallan de maneras
distintas. Un NIT inválido hace que la SAT rechace la factura, pero no tiene nada que ver
con el cobro. Si el `facturador` viviera dentro del `consumer`, un rechazo fiscal
arrastraría consigo la entrega del pago.

Se verificó en producción: el pago `EQD-000216` llegó al banco y al ERP sin problema
mientras su factura era rechazada por un NIT con dígito verificador incorrecto.

---

## Cómo probar el módulo

### Opción A — contra el ambiente desplegado

1. Abrir el monolito en `http://77.113.86.31/admin/login` e iniciar sesión como cajero.
2. Registrar un pago de cualquier boleta pendiente.
3. En menos de un minuto, el pago aparece en la lista con el estado **ENVIADO** y el
   detalle **Banco OK · ERP OK**.
4. La factura electrónica llega por correo al buzón configurado, enviada por DigiFact.

El resultado esperado es que el pago cambie de estado solo, sin que nadie ejecute nada.

### Opción B — levantar los servicios localmente

Requisitos: **JDK 21**, **Maven 3.9+** y credenciales de AWS con acceso a las colas y
tablas de `us-east-2`.

```bash
git clone https://github.com/aleosorio22/payment-dispatcher.git
cd payment-dispatcher
mvn clean install          # compila los cuatro módulos y corre 82 pruebas
```

Cada servicio toma su configuración de variables de entorno. Para arrancar uno:

```bash
java -jar producer/target/producer-1.0.0.jar
java -jar consumer/target/consumer-1.0.0.jar
java -jar facturador/target/facturador-1.0.0.jar
```

**No hay `docker-compose.yml`.** El despliegue es nativo: cada jar corre como un servicio
de systemd en la EC2, con `EnvironmentFile=/etc/payment-dispatcher.env`. Se eligió así
porque los tres servicios comparten una sola máquina pequeña y las dependencias de estado
(colas, tablas) son servicios gestionados de AWS, no contenedores.

### Verificar sin registrar un pago

Las 82 pruebas automatizadas cubren la lógica sin tocar la red:

```bash
mvn test                              # todas
mvn -pl consumer test                 # la clasificación de respuestas y los reintentos
mvn -pl facturador test               # la construcción del documento fiscal y el IVA
```

Las pruebas del `facturador` son el contrato con DigiFact: cada caso sale de un error que
su API devolvió de verdad durante el desarrollo.

### Configuración

Variables que cada servicio necesita. **Los valores no están en el repositorio**; se
entregan por el archivo de entorno de systemd.

| Variable | Servicio | Para qué |
|---|---|---|
| `LARAVEL_BASE_URL` | producer | dónde está el monolito |
| `INTEGRACION_TOKEN` | producer | autenticación con la API del monolito |
| `SIMULADOR_API_KEY` | consumer | llave de las APIs del banco y el ERP |
| `TEAM_CODE` | consumer | prefijo de las llaves de pago |
| `FEL_USUARIO`, `FEL_PASSWORD` | facturador | credenciales de DigiFact |
| `FEL_NIT`, `FEL_NOMBRE`, `FEL_ESTABLECIMIENTO` | facturador | datos fiscales del emisor |
| `FEL_CORREO_EMISOR`, `FEL_CORREO_PRUEBAS` | facturador | buzones del documento |

---

## Estructura del repositorio

```
payment-dispatcher/
├── common/         el mensaje del pago y sus validaciones  (17 pruebas)
├── producer/       reclama al monolito y publica            (6 pruebas)
├── consumer/       entrega al banco y al ERP               (37 pruebas)
├── facturador/     emite la factura electrónica            (22 pruebas)
└── lambda/         avisa al monolito desde el stream
```

Los clientes HTTP del banco y del ERP **no están escritos a mano**: se generan en tiempo de
compilación desde los contratos OpenAPI que publicó cada equipo, con
`openapi-generator-maven-plugin`. Si un contrato cambia, la compilación falla en vez de
fallar en producción.

**Construido con:** Java 21, Spring Boot 3.5.16, Spring Cloud AWS 3.4.2, AWS SDK v2,
Maven multi-módulo.

---

## Evidencia de funcionamiento

Comportamientos verificados en el ambiente desplegado, con pagos reales del sistema:

| Qué se probó | Cómo | Resultado |
|---|---|---|
| Entrega a los dos destinos | pago normal | `EQD-000212`: 201 en ambos, 4 segundos |
| Idempotencia tras acuse perdido | escenario `LOST_ACK` del simulador | `EQD-000211`, `213`, `214`: el reintento recibió 409, no se cobró dos veces |
| Reintentos en memoria | timeout seguido de 500 | `EQD-000214`: resuelto en 7 segundos |
| Reintentos agotados | banco forzado a 500 | `EQD-000215`: 15 intentos en 8 minutos, luego a la cola de descarte |
| Durabilidad del aviso | monolito apagado 5 minutos | ningún aviso perdido |
| Factura certificada | pago normal | `EQD-000218`: autorización `2597E73B-1101-43AB-AE63-B7AA7361804A` |
| Rechazo fiscal aislado | NIT con dígito verificador inválido | `EQD-000216`: factura rechazada, pago entregado igual |

---

## Seguridad

- No hay credenciales en el repositorio ni dentro de los jar. El archivo
  `application-local.yml`, que se usa para pruebas locales, está en `.gitignore` y además
  se excluye del empaquetado desde el POM padre.
- El acceso a SQS y DynamoDB es por rol IAM de la instancia, con permisos acotados a las
  colas y tablas de este proyecto. No hay llaves de acceso que robar.
- El rol del despachador no tiene permiso de `Scan` ni `Query` sobre las tablas: solo puede
  leer y escribir la fila de una llave concreta.
- La API de integración del monolito exige un token de equipo en cada petición.
