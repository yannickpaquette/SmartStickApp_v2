# SmartStick — Application Android Bluetooth

Application Android native (Kotlin) pour contrôler l'ESP32 SmartStick via Bluetooth Classic (SPP).

---

## Fonctionnalités

| Fonctionnalité | Détail |
|---|---|
| Scan Bluetooth | Découvre tous les appareils BT à portée + liste les appareils déjà jumelés |
| Connexion | Jumelage automatique si nécessaire, puis connexion SPP (socket RFCOMM) |
| 4 commandes rapides | Idle (`mot0`), Rigide (`mot1`), Non-Rigide (`mot2`), Auto-Detect (`mot101`) |
| Terminal | Affiche les données reçues de l'ESP32 en temps réel (style hacker vert sur noir) |
| Commande libre | Champ texte pour envoyer n'importe quelle commande personnalisée |
| Déconnexion propre | Bouton dédié, état visible avec indicateur vert/rouge |

---

## Architecture du code

```
app/src/main/
├── java/com/smartstick/bluetooth/
│   ├── MainActivity.kt          # UI + logique de navigation + gestion permissions
│   └── BluetoothSerialService.kt # Connexion BT Classic SPP (threads connectThread + connectedThread)
├── res/
│   ├── layout/activity_main.xml # UI dark theme, inspiré Serial BT Terminal
│   ├── drawable/circle_green.xml
│   ├── drawable/circle_red.xml
│   └── values/
│       ├── colors.xml
│       ├── strings.xml
│       └── themes.xml
└── AndroidManifest.xml          # Permissions BT déclarées (Android 6 à 14)
```

---

## Prérequis

- **Android Studio** Hedgehog (2023.1.1) ou plus récent
- **JDK 17** (inclus avec Android Studio)
- Appareil Android **API 26+** (Android 8.0+)
- ESP32 configuré en mode **Bluetooth Classic avec profil SPP**

> ⚠️ L'application utilise Bluetooth Classic (RFCOMM/SPP), pas BLE. Assurez-vous que l'ESP32 utilise `BluetoothSerial` et non une lib BLE pure.

---

## Installation & Build

### Option A — Android Studio (recommandé)

1. Ouvrir Android Studio → **File > Open** → sélectionner le dossier `SmartStickApp/`
2. Laisser Gradle synchroniser (quelques minutes la première fois)
3. Brancher votre téléphone Android en USB avec **Débogage USB activé**
4. Cliquer sur **▶ Run** (Shift+F10)

### Option B — Ligne de commande

```bash
cd SmartStickApp
./gradlew assembleDebug
# APK généré dans: app/build/outputs/apk/debug/app-debug.apk
```

Installer l'APK sur le téléphone :
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Utilisation

1. **Scanner** → l'app cherche les appareils BT disponibles (~ 12 secondes)
2. **Connecter** → choisir l'ESP32 dans la liste (ex: `HC-05`, `ESP32`)
3. Accepter le jumelage si demandé (PIN: `1234` ou `0000` pour HC-05)
4. Les boutons de commande s'activent → appuyer sur **Rigide**, **Non-Rigide**, etc.
5. Le terminal affiche les réponses de l'ESP32 en temps réel

---

## Commandes envoyées

| Bouton | Commande envoyée | Description |
|---|---|---|
| Idle | `mot0\n` | Arrêt / mode repos |
| Rigide | `mot1\n` | Mode manuel rigide |
| Non-Rigide | `mot2\n` | Mode manuel non-rigide |
| Auto-Detect | `mot101\n` | Détection automatique |

> Un `\n` (newline) est automatiquement ajouté à chaque commande pour que l'ESP32 puisse les délimiter avec `Serial.readStringUntil('\n')`.

---

## Permissions Android

| Permission | Raison |
|---|---|
| `BLUETOOTH_CONNECT` (API 31+) | Connexion aux appareils |
| `BLUETOOTH_SCAN` (API 31+) | Scan des appareils |
| `ACCESS_FINE_LOCATION` (API <31) | Requis par Android pour le scan BT |
| `BLUETOOTH` + `BLUETOOTH_ADMIN` (API <31) | Rétrocompatibilité |

---

## Côté ESP32 — Code minimal attendu

```cpp
#include "BluetoothSerial.h"
BluetoothSerial SerialBT;

void setup() {
  Serial.begin(115200);
  SerialBT.begin("SmartStick"); // Nom visible par l'app Android
}

void loop() {
  if (SerialBT.available()) {
    String cmd = SerialBT.readStringUntil('\n');
    cmd.trim();
    
    if (cmd == "mot0") { /* Idle */ }
    else if (cmd == "mot1") { /* Rigide */ }
    else if (cmd == "mot2") { /* Non-Rigide */ }
    else if (cmd == "mot101") { /* Auto-Detect */ }
    
    SerialBT.println("ACK:" + cmd); // Réponse visible dans le terminal
  }
}
```

---

## Dépannage

| Problème | Solution |
|---|---|
| "Connexion échouée" | Vérifier que l'ESP32 est en BT Classic (pas BLE). Réessayer après un reset de l'ESP32. |
| Appareil non visible | Aller dans Paramètres Android > Bluetooth et jumeler manuellement d'abord |
| Permissions refusées | Aller dans Paramètres > Apps > SmartStick > Permissions et activer Bluetooth + Localisation |
| Commandes non reçues | Vérifier que l'ESP32 lit avec `readStringUntil('\n')` et non `read()` byte par byte |
