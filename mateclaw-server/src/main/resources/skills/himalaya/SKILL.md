---
name: himalaya
description: "CLI to manage emails via IMAP/SMTP. Use himalaya to list, read, write, reply, forward, search, and organize emails from the terminal. Supports multiple accounts and message composition with MML (MIME Meta Language)."
optional: true
dependencies:
  commands:
    - himalaya
platforms:
  - macos
  - linux
---

# Himalaya Email CLI

Himalaya is a CLI email client that lets you manage emails from the terminal using IMAP, SMTP, Notmuch, or Sendmail backends.

## References

- `references/configuration.md` (config file setup + IMAP/SMTP authentication)

## Prerequisites

1. **Himalaya CLI** - the `himalaya` binary must already be on `PATH`. Check with `himalaya --version`.
   - **Recommended: v1.2.0 or newer.** Older releases can fail against some IMAP servers.
2. A configuration file at `~/.config/himalaya/config.toml`
3. IMAP/SMTP credentials configured (password stored securely)

## Configuration Setup

Run the interactive wizard to set up an account:

```bash
himalaya account configure default
```

Or create `~/.config/himalaya/config.toml` manually:

```toml
[accounts.personal]
email = "you@example.com"
display-name = "Your Name"
default = true

backend.type = "imap"
backend.host = "imap.example.com"
backend.port = 993
backend.encryption.type = "tls"
backend.login = "you@example.com"
backend.auth.type = "password"
backend.auth.cmd = "pass show email/imap"  # or use keyring

message.send.backend.type = "smtp"
message.send.backend.host = "smtp.example.com"
message.send.backend.port = 587
message.send.backend.encryption.type = "start-tls"
message.send.backend.login = "you@example.com"
message.send.backend.auth.type = "password"
message.send.backend.auth.cmd = "pass show email/smtp"
```

If using 163 mail, add `backend.extensions.id.send-after-auth = true`.

## Common Operations

### List Folders

```bash
himalaya folder list
```

### List Emails

```bash
himalaya envelope list                           # INBOX (default)
himalaya envelope list --folder "Sent"           # Specific folder
himalaya envelope list --page 1 --page-size 20   # With pagination
```

### Search Emails

```bash
himalaya envelope list from john@example.com subject meeting
```

### Read an Email

```bash
himalaya message read 42          # Plain text
himalaya message export 42 --full # Raw MIME
```

### Send / Compose Emails

**Recommended:** Use `template write | template send` pipeline:

```bash
export EDITOR=cat
himalaya template write \
  -H "To: recipient@example.com" \
  -H "Subject: Email Subject" \
  "Email body content" | himalaya template send
```

**With CC:**

```bash
export EDITOR=cat
himalaya template write \
  -H "To: recipient@example.com" \
  -H "Cc: cc@example.com" \
  -H "Subject: Email Subject" \
  "Email body content" | himalaya template send
```

**With attachments (Python fallback):**

```python
import smtplib
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
from email.mime.base import MIMEBase
from email import encoders

msg = MIMEMultipart()
msg['From'] = 'sender@example.com'
msg['To'] = 'recipient@example.com'
msg['Subject'] = 'Email with attachment'
msg.attach(MIMEText('Email body', 'plain'))

with open('/path/to/file.pdf', 'rb') as f:
    part = MIMEBase('application', 'octet-stream')
    part.set_payload(f.read())
    encoders.encode_base64(part)
    part.add_header('Content-Disposition', 'attachment; filename="file.pdf"')
    msg.attach(part)

server = smtplib.SMTP_SSL('smtp.example.com', 465)
server.login('sender@example.com', 'password')
server.send_message(msg)
server.quit()
```

**Known limitations:**
- MML attachment parsing may fail in himalaya v1.1.0 - use Python for attachments
- `message write` hangs in non-interactive mode - use `template write | template send`
- `message send` may fail with header parsing - use `template send`

**Configuration requirement:** Set `message.send.save-to-folder` in config.toml:

```toml
[accounts.default]
message.send.save-to-folder = "Sent"
```

### Move/Copy Emails

```bash
himalaya message move 42 "Archive"
himalaya message copy 42 "Important"
```

### Delete an Email

```bash
himalaya message delete 42
```

### Manage Flags

```bash
himalaya flag add 42 --flag seen
himalaya flag remove 42 --flag seen
```

## Multiple Accounts

```bash
himalaya account list                      # List accounts
himalaya --account work envelope list      # Use specific account
```

## Attachments

```bash
himalaya attachment download 42              # Save attachments
himalaya attachment download 42 --dir ~/dl   # Save to directory
```

## Output Formats

```bash
himalaya envelope list --output json
himalaya envelope list --output plain
```

## Debugging

```bash
RUST_LOG=debug himalaya envelope list
RUST_LOG=trace RUST_BACKTRACE=1 himalaya envelope list
```

## Tips

- Message IDs are relative to the current folder; re-list after folder changes.
- Store passwords securely using `pass`, system keyring, or a command.
- **For automation:** Always use `template write | template send` with `export EDITOR=cat`.
- **163 Mail:** Set `backend.extensions.id.send-after-auth = true` and `message.send.save-to-folder = "Sent"`.
- **Folder names:** Use English folder names for better compatibility.
