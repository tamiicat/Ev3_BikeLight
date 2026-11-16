#include <Adafruit_NeoPixel.h>
#include <WiFiS3.h>
#include <WiFiUdp.h>
#include <ArduinoJson.h>   // ArduinoJson v6 (si usas v7, cambia StaticJsonDocument<256> por JsonDocument)

const char* WIFI_SSID = "RED BIKELIGHT";
const char* WIFI_PASSWORD = "75648955!";
IPAddress ip_ESP32(192, 168, 4, 1);

#define TRIG_PIN 12
#define ECHO_PIN 13
#define LED_PIN 5
#define NUM_LEDS 10
#define PASO_GRADUAL_MS 120   // Velocidad del efecto gradual izquierda/derecha (ms por LED)
#define DISTANCIA_UMBRAL 10
#define PUERTO_UDP 4210
#define NUM_MEDICIONES 3
#define INTERVALO_MEDICION_MS 150

Adafruit_NeoPixel strip(NUM_LEDS, LED_PIN, NEO_GRB + NEO_KHZ800);
WiFiUDP udp;

bool ultimo_estado = false;
bool primer_envio = true;
bool presencia = false;

// Suscripción de la App a las lecturas del sensor (en tiempo real)
#define SUSCRIPCION_MS 5000      // la App renueva la suscripción cada ~2 s
IPAddress cliente_ip;
uint16_t cliente_puerto = 0;
unsigned long cliente_hasta = 0;

// Variables de control remoto (las modifica la App)
bool modo_manual = false;
bool estado_manual_led = false;
int indice_color = 1;   // 1..7 (igual que la lista de la App)
int indice_efecto = 1;  // 1..6 (igual que la lista de la App)

// Paletas: índice 0 = respaldo, 1..7 = opciones de la App
const uint8_t PALETAS[8][3][3] = {
  {{0, 255, 0},   {0, 255, 0},     {0, 255, 0}},      // 0 respaldo
  {{255, 0, 0},   {255, 255, 0},   {0, 255, 0}},      // 1 Clásico
  {{0, 255, 255}, {255, 0, 255},   {255, 255, 0}},    // 2 Neón
  {{0, 0, 255},   {255, 255, 255}, {255, 0, 0}},      // 3 Emergencia
  {{64, 224, 208},{50, 205, 50},   {255, 255, 255}},  // 4 Flúor
  {{0, 0, 255},   {138, 43, 226},  {255, 0, 0}},      // 5 Arcoíris
  {{0, 128, 0},   {0, 255, 0},     {255, 255, 0}},    // 6 Eco
  {{255, 0, 0},   {255, 140, 0},   {255, 255, 0}}     // 7 Fuego
};

float medir_distancia() {
  long duraciones[NUM_MEDICIONES];
  int lectura_valida = 0;

  for (int i = 0; i < NUM_MEDICIONES; i++) {
    digitalWrite(TRIG_PIN, LOW);
    delayMicroseconds(2);
    digitalWrite(TRIG_PIN, HIGH);
    delayMicroseconds(10);
    digitalWrite(TRIG_PIN, LOW);
    long duracion = pulseIn(ECHO_PIN, HIGH, 15000);
    if (duracion > 30 && duracion < 15000) {
      duraciones[lectura_valida] = duracion;
      lectura_valida++;
    }
    delayMicroseconds(100);
  }

  if (lectura_valida == 0) return 999;
  long duracion_promedio = 0;
  for (int i = 0; i < lectura_valida; i++) {
    duracion_promedio += duraciones[i];
  }
  return ((duracion_promedio / lectura_valida) * 0.0343) / 2;
}

void conectar_WiFi() {
  Serial.print("Conectando a la red: ");
  Serial.println(WIFI_SSID);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  int intentos = 0;
  while (WiFi.status() != WL_CONNECTED && intentos < 20) {
    delay(500);
    Serial.print(".");
    intentos++;
  }
  if (WiFi.status() == WL_CONNECTED) {
    Serial.print("\nConectado. IP del Nodo A: ");
    Serial.println(WiFi.localIP());
    // (Re)abrir el socket UDP cada vez que se reconecta
    udp.stop();
    udp.begin(PUERTO_UDP);
  }
}

void enviar_estado(bool encendido) {
  udp.beginPacket(ip_ESP32, PUERTO_UDP);
  udp.write(encendido ? '1' : '0');
  udp.endPacket();
}

bool cliente_activo() {
  return cliente_puerto != 0 && (long)(cliente_hasta - millis()) > 0;
}

// Envía la distancia (cm, -1 = sin lectura) a la App suscrita
void enviar_telemetria(int cm) {
  char msg[64];
  snprintf(msg, sizeof(msg), "{\"evt\":\"dist\",\"cm\":%d,\"umbral\":%d}", cm, DISTANCIA_UMBRAL);
  udp.beginPacket(cliente_ip, cliente_puerto);
  udp.print(msg);
  udp.endPacket();
}

// ---------- Dibujo de LEDs ----------

uint32_t color_pixel(int i) {
  int p = (indice_color >= 1 && indice_color <= 7) ? indice_color : 0;
  const uint8_t* c = PALETAS[p][i % 3];
  return strip.Color(c[0], c[1], c[2]);
}

void pintar_todos() {
  for (int i = 0; i < NUM_LEDS; i++) strip.setPixelColor(i, color_pixel(i));
}

// Dibuja un fotograma según el efecto elegido en la App (sin bloquear el loop)
void dibujar_leds(bool encendido) {
  static bool estaba_encendido = false;
  static unsigned long inicio_efecto = 0;
  strip.clear();

  if (encendido) {
    unsigned long t = millis();
    if (!estaba_encendido) inicio_efecto = t;  // la animación siempre parte desde el primer LED

    switch (indice_efecto) {
      case 2:  // Parpadeo lento
        if ((t / 500) % 2 == 0) pintar_todos();
        break;

      case 3:  // Parpadeo rápido
        if ((t / 150) % 2 == 0) pintar_todos();
        break;

      case 4:  // Hacia la izquierda (gradual): 1, luego 1-2, luego 1-2-3 ... partiendo desde el extremo derecho
      case 5: {  // Hacia la derecha (gradual): 1, luego 1-2, luego 1-2-3 ... partiendo desde el extremo izquierdo
        // Ciclo: NUM_LEDS pasos de llenado + 1 paso con todo encendido + 1 paso apagado
        const int ciclo = NUM_LEDS + 2;
        int paso = ((t - inicio_efecto) / PASO_GRADUAL_MS) % ciclo;
        int cuantos = (paso < NUM_LEDS) ? paso + 1 : (paso == NUM_LEDS ? NUM_LEDS : 0);
        for (int k = 0; k < cuantos; k++) {
          // Derecha: LED 0,1,2... | Izquierda: LED 9,8,7...
          int led = (indice_efecto == 5) ? k : (NUM_LEDS - 1 - k);
          strip.setPixelColor(led, color_pixel(led));
        }
        break;
      }

      case 6: {  // División en el centro: se abre desde el centro hacia los extremos
        int mitad = NUM_LEDS / 2;
        int paso = (t / 150) % (mitad + 2);
        for (int k = 0; k < paso && k < mitad; k++) {
          strip.setPixelColor(mitad - 1 - k, color_pixel(mitad - 1 - k));
          strip.setPixelColor(mitad + k, color_pixel(mitad + k));
        }
        break;
      }

      default:  // 1: Fijo / Continuo
        pintar_todos();
        break;
    }
  }
  estaba_encendido = encendido;
  strip.show();
}

// ---------- Comandos UDP desde la App ----------

// Devuelve: 0 = comando dirigido a otro nodo (se ignora), 1 = OK, -1 = JSON inválido,
//           2 = suscripción a lecturas (sin ACK)
int procesar_comando(const char* json, IPAddress remoto, uint16_t puertoRemoto) {
  StaticJsonDocument<256> doc;
  DeserializationError error = deserializeJson(doc, json);
  if (error) {
    Serial.print("JSON invalido: ");
    Serial.println(error.c_str());
    return -1;
  }

  // Si trae "nodo" y no es "A", el comando es para el ESP32
  const char* nodo = doc["nodo"] | "";
  if (nodo[0] != 0 && nodo[0] != 'A') return 0;

  // La App pide recibir las lecturas del sensor: se las enviamos a quien lo pidió
  if (doc["suscribir"] | false) {
    cliente_ip = remoto;
    cliente_puerto = puertoRemoto;
    cliente_hasta = millis() + SUSCRIPCION_MS;
    return 2;
  }

  if (!doc["brillo"].isNull()) {
    int brillo = constrain((int)doc["brillo"], 0, 100);
    strip.setBrightness(map(brillo, 0, 100, 0, 255));
  }
  if (!doc["color"].isNull())  indice_color  = constrain((int)doc["color"], 1, 7);
  if (!doc["efecto"].isNull()) indice_efecto = constrain((int)doc["efecto"], 1, 6);

  const char* modo = doc["modo"] | "";
  if (strcmp(modo, "manual") == 0) {
    modo_manual = true;
    estado_manual_led = doc["estado"] | false;
  } else if (strcmp(modo, "auto") == 0) {
    modo_manual = false;
  }

  Serial.print("Comando OK -> modo_manual=");
  Serial.print(modo_manual);
  Serial.print(" estado=");
  Serial.print(estado_manual_led);
  Serial.print(" color=");
  Serial.print(indice_color);
  Serial.print(" efecto=");
  Serial.println(indice_efecto);
  return 1;
}

void leer_udp() {
  int packetSize = udp.parsePacket();
  if (!packetSize) return;

  char buffer[256];
  int len = udp.read(buffer, sizeof(buffer) - 1);
  if (len <= 0) return;
  buffer[len] = 0;

  // Solo procesamos JSON (ignora los '1'/'0' u otros datos)
  if (buffer[0] != '{') return;

  IPAddress remoto = udp.remoteIP();
  uint16_t puertoRemoto = udp.remotePort();

  int r = procesar_comando(buffer, remoto, puertoRemoto);
  if (r == 0 || r == 2) return;  // 0: era para el otro nodo / 2: suscripción -> no hay ACK

  // ACK: la App espera esta respuesta para confirmar que el comando llegó
  udp.beginPacket(remoto, puertoRemoto);
  udp.print(r == 1 ? "{\"ok\":true,\"nodo\":\"A\"}" : "{\"ok\":false,\"nodo\":\"A\"}");
  udp.endPacket();
}

// ---------- Arduino ----------

void setup() {
  Serial.begin(115200);
  delay(1000);
  pinMode(TRIG_PIN, OUTPUT);
  pinMode(ECHO_PIN, INPUT);

  strip.begin();
  strip.setBrightness(127);
  strip.show();

  conectar_WiFi();   // dentro se abre el socket UDP
}

void loop() {
  if (WiFi.status() != WL_CONNECTED) {
    conectar_WiFi();
  }

  leer_udp();

  // Medición de distancia: en modo automático, o si hay una App mirando el sensor
  static unsigned long ultima_medicion = 0;
  unsigned long ahora = millis();
  bool hay_cliente = cliente_activo();
  if ((!modo_manual || hay_cliente) && ahora - ultima_medicion >= INTERVALO_MEDICION_MS) {
    ultima_medicion = ahora;
    float distancia = medir_distancia();
    presencia = (distancia < DISTANCIA_UMBRAL);

    // El estado hacia el ESP32 (LEDs/buzzer) solo se informa en modo automático
    if (!modo_manual && (presencia != ultimo_estado || primer_envio)) {
      enviar_estado(presencia);
      ultimo_estado = presencia;
      primer_envio = false;
    }

    // Lectura en tiempo real para la App (-1 = sin eco / fuera de alcance)
    if (hay_cliente) enviar_telemetria(distancia >= 999 ? -1 : (int)distancia);
  }

  bool encendido = modo_manual ? estado_manual_led : presencia;
  dibujar_leds(encendido);
  delay(20);
}
