#include <Adafruit_NeoPixel.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <ArduinoJson.h>   // ArduinoJson v6 (si usas v7, cambia StaticJsonDocument<256> por JsonDocument)

// Debe ser IGUAL al SSID/clave que usan el Arduino (Nodo A) y la App
const char* AP_SSID = "RED BIKELIGHT";
const char* AP_PASSWORD = "75648955!";

#define LED_PIN 5
#define BUZZER_PIN 4
#define NUM_LEDS 10
#define PASO_GRADUAL_MS 120   // Velocidad del efecto gradual izquierda/derecha (ms por LED)
#define PUERTO_UDP 4210
#define BRILLO_MAXIMO 80   // Tope de brillo (0-255). La App envía 0-100% y se escala a este tope.

// Tipo de buzzer:
//   0 = ACTIVO (el que traías: HIGH/LOW). Suena a su frecuencia fija; la frecuencia de la App se ignora.
//   1 = PASIVO (necesita señal PWM). Suena a la frecuencia elegida en la App.
#define BUZZER_PASIVO 0
#define BUZZER_CANAL 0     // Canal LEDC (solo para buzzer pasivo con ESP32 core 2.x)

Adafruit_NeoPixel strip(NUM_LEDS, LED_PIN, NEO_GRB + NEO_KHZ800);
WiFiUDP udp;

// ---------- Estado ----------
bool alerta = false;            // true cuando el Arduino (Nodo A) detecta proximidad ('1' / '0')

// LEDs (configurables desde la App)
bool modo_manual = false;
bool estado_manual_led = false;
int indice_color = 0;           // 0 = rojo fijo (comportamiento original); 1..7 = paletas de la App
int indice_efecto = 1;          // 1..6 (igual que la App)

// Buzzer (configurable desde la App)
bool buzzer_activo = true;           // si suena o no cuando hay alerta
int buzzer_frec = 2000;              // Hz (solo tiene efecto con buzzer pasivo)
int buzzer_dur = 1000;               // ms por pitido; >= 1000 = tono continuo mientras dure la alerta
unsigned long buzzer_prueba_hasta = 0;  // la App pide un pitido de prueba de buzzer_dur ms

// Paletas: índice 0 = rojo por defecto, 1..7 = opciones de la App
const uint8_t PALETAS[8][3][3] = {
  {{255, 0, 0},   {255, 0, 0},     {255, 0, 0}},      // 0 rojo por defecto
  {{255, 0, 0},   {255, 255, 0},   {0, 255, 0}},      // 1 Clásico
  {{0, 255, 255}, {255, 0, 255},   {255, 255, 0}},    // 2 Neón
  {{0, 0, 255},   {255, 255, 255}, {255, 0, 0}},      // 3 Emergencia
  {{64, 224, 208},{50, 205, 50},   {255, 255, 255}},  // 4 Flúor
  {{0, 0, 255},   {138, 43, 226},  {255, 0, 0}},      // 5 Arcoíris
  {{0, 128, 0},   {0, 255, 0},     {255, 255, 0}},    // 6 Eco
  {{255, 0, 0},   {255, 140, 0},   {255, 255, 0}}     // 7 Fuego
};

// ---------- LEDs ----------

uint32_t color_pixel(int i) {
  int p = (indice_color >= 1 && indice_color <= 7) ? indice_color : 0;
  const uint8_t* c = PALETAS[p][i % 3];
  return strip.Color(c[0], c[1], c[2]);
}

void pintar_todos() {
  for (int i = 0; i < NUM_LEDS; i++) strip.setPixelColor(i, color_pixel(i));
}

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

      case 6: {  // División en el centro
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

// ---------- Buzzer ----------

void buzzer_iniciar() {
#if BUZZER_PASIVO
  #if ESP_ARDUINO_VERSION_MAJOR >= 3
    ledcAttach(BUZZER_PIN, 2000, 8);
  #else
    ledcSetup(BUZZER_CANAL, 2000, 8);
    ledcAttachPin(BUZZER_PIN, BUZZER_CANAL);
  #endif
#endif
}

// Enciende/apaga el buzzer físico (solo escribe cuando algo cambia)
void buzzer_salida(bool encendido) {
  static bool ultimo_estado = false;
  static int ultima_frec = 0;
  if (encendido == ultimo_estado && (!encendido || ultima_frec == buzzer_frec)) return;
  ultimo_estado = encendido;
  ultima_frec = buzzer_frec;

#if BUZZER_PASIVO
  int f = encendido ? buzzer_frec : 0;
  #if ESP_ARDUINO_VERSION_MAJOR >= 3
    ledcWriteTone(BUZZER_PIN, f);
  #else
    ledcWriteTone(BUZZER_CANAL, f);
  #endif
#else
  digitalWrite(BUZZER_PIN, encendido ? HIGH : LOW);
#endif
}

void actualizar_buzzer() {
  bool prueba = (long)(buzzer_prueba_hasta - millis()) > 0;
  bool nivel = false;

  if (prueba) {
    nivel = true;  // pitido de prueba pedido desde la App
  } else if (buzzer_activo && alerta) {
    if (buzzer_dur >= 1000) {
      nivel = true;  // tono continuo
    } else {
      // Pitidos de buzzer_dur ms con una pausa igual (mínimo 100 ms)
      unsigned long pausa = (buzzer_dur > 100) ? buzzer_dur : 100;
      unsigned long ciclo = buzzer_dur + pausa;
      nivel = (millis() % ciclo) < (unsigned long)buzzer_dur;
    }
  }
  buzzer_salida(nivel);
}

// ---------- Comandos UDP ----------

// Devuelve: 0 = comando dirigido a otro nodo (se ignora), 1 = OK, -1 = JSON inválido
int procesar_comando(const char* json) {
  StaticJsonDocument<256> doc;
  if (deserializeJson(doc, json)) return -1;

  // Si trae "nodo" y no es "B", el comando es para el Arduino
  const char* nodo = doc["nodo"] | "";
  if (nodo[0] != 0 && nodo[0] != 'B') return 0;

  // --- LEDs ---
  if (!doc["brillo"].isNull()) {
    int brillo = constrain((int)doc["brillo"], 0, 100);
    strip.setBrightness(map(brillo, 0, 100, 0, BRILLO_MAXIMO));
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

  // --- Buzzer ---
  if (!doc["buzzer_activo"].isNull()) buzzer_activo = doc["buzzer_activo"] | true;
  if (!doc["buzzer_frec"].isNull())   buzzer_frec = constrain((int)doc["buzzer_frec"], 200, 8000);
  if (!doc["buzzer_dur"].isNull())    buzzer_dur  = constrain((int)doc["buzzer_dur"], 20, 2000);
  if (!doc["buzzer_prueba"].isNull()) {
    bool p = doc["buzzer_prueba"] | false;
    buzzer_prueba_hasta = p ? (millis() + buzzer_dur) : 0;
  }

  Serial.print("Comando OK -> manual=");
  Serial.print(modo_manual);
  Serial.print(" led=");
  Serial.print(estado_manual_led);
  Serial.print(" color=");
  Serial.print(indice_color);
  Serial.print(" efecto=");
  Serial.print(indice_efecto);
  Serial.print(" buzzer_activo=");
  Serial.print(buzzer_activo);
  Serial.print(" frec=");
  Serial.print(buzzer_frec);
  Serial.print(" dur=");
  Serial.println(buzzer_dur);
  return 1;
}

void leer_udp() {
  int tamano = udp.parsePacket();
  if (tamano <= 0) return;

  char buffer[256];
  int len = udp.read(buffer, sizeof(buffer) - 1);
  if (len <= 0) return;
  buffer[len] = '\0';

  IPAddress remoto = udp.remoteIP();
  uint16_t puertoRemoto = udp.remotePort();

  // 1) Mensaje del Arduino (Nodo A): '1' = proximidad detectada, '0' = libre
  if (buffer[0] == '1' || buffer[0] == '0') {
    alerta = (buffer[0] == '1');
    Serial.print("Nodo A -> alerta=");
    Serial.println(alerta);
    return;
  }

  // 2) Comando JSON desde la App
  if (buffer[0] == '{') {
    int r = procesar_comando(buffer);
    if (r == 0) return;  // era para el otro nodo: no responde

    // ACK: la App espera esta respuesta para confirmar que el comando llegó
    udp.beginPacket(remoto, puertoRemoto);
    udp.print(r == 1 ? "{\"ok\":true,\"nodo\":\"B\"}" : "{\"ok\":false,\"nodo\":\"B\"}");
    udp.endPacket();
  }
}

// ---------- Arduino framework ----------

void setup() {
  Serial.begin(115200);
  delay(3000);
  Serial.flush();

  pinMode(BUZZER_PIN, OUTPUT);
  digitalWrite(BUZZER_PIN, LOW);
  buzzer_iniciar();

  strip.begin();
  strip.setBrightness(BRILLO_MAXIMO);
  strip.show();

  WiFi.mode(WIFI_AP);
  WiFi.softAP(AP_SSID, AP_PASSWORD);

  IPAddress ip = WiFi.softAPIP();

  Serial.println("\n");
  Serial.println("══════════════════════════════════");
  Serial.println(" NODO B - ESP32 PUNTO DE ACCESO ");
  Serial.println("══════════════════════════════════");
  Serial.print("Red creada: ");
  Serial.println(AP_SSID);
  Serial.print("IP del ESP32 (fija): ");
  Serial.println(ip);

  udp.begin(PUERTO_UDP);
  Serial.println("\n Esperando paquetes...\n");
}

void loop() {
  leer_udp();

  bool leds_on = modo_manual ? estado_manual_led : alerta;
  dibujar_leds(leds_on);
  actualizar_buzzer();

  delay(20);
}
