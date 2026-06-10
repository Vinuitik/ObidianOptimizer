# Encryption & Security Architecture

## Threat Model & Goal
The primary objective is absolute privacy for personal thoughts and diaries. The system must defend against:
1.  **Cloud Provider Compromise**: Google Drive accessing or leaking the backups.
2.  **Physical Device Seizure**: Laptops or phones being taken and inspected via court summons or theft.
3.  **Future-Proofing (Quantum)**: Resistance against future quantum computing decryption capabilities.

*Note on Cryptography*: SHA (e.g., SHA-512) is a *hashing* algorithm (one-way), not an *encryption* algorithm (two-way). For encrypting data so it can be decrypted, we use Symmetric Encryption. To achieve "quantum-resistant overkill," we use **AES-256** combined with extreme key-derivation.

---

## 1. Cloud Sync Encryption (Google Drive Protection)
Data must NEVER be in plaintext on Google servers.

*   **The Mechanism**: Before the Laptop backend uploads the "Master State" `.zip` (or outbox `.patch` files from mobile) to Google Drive, it encrypts the file locally.
*   **The Cipher**: **AES-256-GCM**. AES-256 is structurally resistant to quantum computer attacks (Grover's algorithm only reduces its effective strength to 128-bit, which remains unbreakable).
*   **Key Derivation**: **Argon2id**. We will use Argon2id with "paranoia-level" work factors (e.g., forcing it to consume 2GB of RAM and take 2 seconds to derive the key). This makes brute-forcing a captured ZIP file mathematically impossible, even for state actors.
*   **Workflow**: 
    1. Laptop zips vault → Encrypts zip with AES-256 → Uploads `.enc` file to Google Drive.
    2. Mobile app downloads `.enc` from Drive → Decrypts locally in memory using your master passphrase → Parses data.

## 2. Local Device Encryption (Physical Seizure Protection)

If a device is physically summoned or stolen, protecting the cloud isn't enough; the local storage must be encrypted.

### Laptop (The Source of Truth)
Since Obsidian operates on raw `.md` files, encrypting individual files breaks the Obsidian app experience. 
*   **Solution**: **Full Disk Encryption (FDE) or Containerization.**
*   Instead of writing a custom Java encrypter, your Obsidian vault should live entirely inside a **VeraCrypt** encrypted container, or on a drive encrypted with **BitLocker** (with a pre-boot PIN).
*   If the laptop is powered off, the data is cryptographic noise.

### Mobile App (The Client)
The mobile app stores data in a local SQLite database for offline access.
*   **Solution**: **SQLCipher**.
*   We will replace the standard `expo-sqlite` driver with `SQLCipher`. This is an open-source extension to SQLite that provides transparent 256-bit AES encryption of database files.
*   Upon opening the app, it prompts for a quick PIN/Biometric or master password to derive the key and unlock the SQLite database. When the app is force-closed, the DB is locked.

## Summary of the "Paranoia" Setup
*   **Key Generation**: Argon2id (Overkill parameters: high memory, high iteration cost).
*   **Payload Encryption (Cloud)**: AES-256-GCM.
*   **Laptop Storage**: VeraCrypt AES-256 hidden volume or BitLocker.
*   **Mobile Storage**: SQLCipher (AES-256).

This architecture ensures that even with infinite time and resources, anyone possessing your Google Drive data or your powered-off devices cannot read your journals.