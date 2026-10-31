# Layers

**A calm, private note-taking app for Android where every photo, drawing, PDF and file can hold notes *underneath* it.**

Layers works like a stack of fine paper. A note is a sheet; long-press a photo in it and you can slide another sheet underneath — a *sub-layer* — or drop numbered pins on exact spots, each with its own note. Sub-layers can hold anything a note can, including their own photos with their own layers, as deep as you like.

Built for the Samsung Galaxy S26 Ultra and its S Pen, in Kotlin with Jetpack Compose and Material 3.

---

## Features

### Organize in layers
- Unlimited nested folders with a name, accent color and icon
- Create, rename, move (“Move to…” with the full folder tree) and delete folders and notes
- Breadcrumbs everywhere: *Home › Work › Project X*
- Grid or list view; sort by date modified, date created or title
- Pin notes to the top, give notes a color, add multiple tags and filter by tag

### A block-based editor
- Notes are ordered blocks: rich text, checklist items, images, drawings, voice recordings, PDFs and any file
- Rich text: bold, italic, underline, strikethrough, highlight, two heading sizes, bulleted and numbered lists
- A floating formatting toolbar above the keyboard, with an **Insert** button for every block type
- Photos from the system Photo Picker or the camera, “Insert latest screenshot”, PDFs and any file
- Every attachment is copied into the app’s private storage; large images are downsampled for display, originals are kept
- Autosave while you type, undo/redo, drag handles to reorder blocks

### Sub-layers — the signature feature
- Long-press an image, drawing, PDF, recording or file: **Open / Add layer**, **Add pin here**, **Copy text from image**, **Replace**, **Delete**
- Numbered pins on images, stored as normalized coordinates so they stay put at any size; each pin opens its own sub-note; pins can be hidden
- Long-press a PDF page to give that page its own layer
- Badges show how many layers sit under a block; cards with layers show sheets peeking out underneath
- A “dive in” shared-element transition: the image zooms and expands into its sub-note, and zooms back out
- Breadcrumbs include layer depth (*Folder › Note › Photo · Pin 2 › Sub-note*); the deeper you go, the deeper the paper tone
- A **Layers outline** shows the whole tree under a note
- Deleting anything with layers asks first, tells you how many sub-layers are affected, and moves them all to the trash

### Tasks, calendar and reminders
- Any checklist item can become a task with a due date, time, reminder and repeat (daily, weekly, monthly)
- Month calendar with dots on busy days and an agenda for the selected day; overdue tasks are flagged with an icon and the word “Overdue”, not just color
- Exact alarms when allowed (with guidance to the setting), inexact fallback otherwise; “Mark done” and “Snooze 1 hour” right in the notification; reminders are restored after a reboot
- “Add to phone calendar” for any task — no calendar permission needed

### S Pen
- Pen, highlighter and stroke eraser; color palette; adjustable thickness; pressure sensitivity
- Palm rejection: once the S Pen is detected, only the pen draws and fingers pan and zoom
- Hold the S Pen side button to erase temporarily
- Strokes are stored as vector data (JSON), never as bitmaps
- Annotate PDF pages with ink saved separately from the original, and export a flattened, annotated PDF

### Locking and privacy
- Lock individual notes or whole folders; sub-layers inherit the lock
- Unlock with fingerprint, face or your screen lock (BiometricPrompt)
- Real encryption: AES-256-GCM with a key in the Android Keystore, for both note content and attachment files. The key survives new fingerprint enrollments
- Locked notes show only their title in lists, never appear in search or the widget, re-lock as soon as you leave the app, and block screenshots and the recent-apps preview
- Optional app lock
- **No analytics, no accounts, no network access.** The app removes the internet permission entirely; your notes leave the phone only through backups and shares you start

### PDFs
- Built-in viewer: pinch zoom, smooth scrolling, page thumbnails, jump to page
- Text search inside PDFs on Android 15+ (hidden on older versions)
- Shows up in “Open with” for PDFs from any app, with “Save to folder”

### Search
- Full-text search (Room FTS4) across titles, text, checklists, text inside images (on-device ML Kit OCR), PDF text and every sub-layer
- Search as you type, highlighted snippets, the full path including layer depth, and “Found in image” / “Found in PDF” labels
- Filters for folder, tag, tasks, images, PDFs and date range; recent searches

### Capture and share
- Share text, links, images, PDFs and files *into* Layers from any app, as a new note or into an existing one
- Quick Settings tiles: **New note** and **Note from last screenshot**
- App shortcuts: New note, New task, Search
- Share notes *out* as plain text, PDF or images; share individual attachments
- Home-screen widget with today’s tasks (tap to check off) and quick-capture buttons

### Safety nets
- Trash keeps deleted items for 30 days, then purges them automatically
- Encrypted backups (AES-256-GCM, key from PBKDF2-HMAC-SHA256 with 310,000 iterations) of everything — folders, notes, layers, attachments, locked notes and settings — saved anywhere via the system file picker (Google Drive, Downloads…)
- Optional weekly automatic backup to a folder you choose, keeping the five newest
- Restore on a new phone, with a clear warning before anything is replaced

---

## Tech

| Area | Choice |
| --- | --- |
| Language | Kotlin 2.2 |
| UI | Jetpack Compose, Material 3, shared-element transitions, spring animations |
| Widget | Jetpack Glance |
| Architecture | MVVM, ViewModels exposing `StateFlow`, repositories over Room, manual dependency injection |
| Storage | Room (with FTS4, schema export), DataStore, app-private files |
| Background | WorkManager (OCR, PDF text, trash purge, auto-backup), AlarmManager (reminders) |
| Images | Coil |
| Rich text | compose-rich-editor (HTML storage) |
| Reordering | Calvin Liang’s Reorderable |
| OCR | ML Kit Text Recognition, bundled on-device Latin model |
| Security | Android Keystore, AES-256-GCM, BiometricPrompt, FLAG_SECURE |
| Min / target SDK | 29 / 36 |

The drawing engine is a custom Compose/Canvas implementation (Jetpack Ink had no stable release when this was written), with vector strokes, pressure and palm rejection.

## Building

1. Open the project folder in Android Studio and let Gradle sync.
2. Connect your phone with USB debugging on and press **Run**.

Permissions are requested only when a feature first needs them: notifications and “Alarms & reminders” when you set a reminder, the microphone when you record, photo access for “latest screenshot”. Locking needs a screen lock (PIN, pattern, password or biometrics) set up on the phone.

## Project layout

```
app/src/main/java/com/layers/app
├── data/          Room entities and DAOs, block model, repositories, attachments
├── security/      Keystore crypto, lock session, BiometricPrompt, FLAG_SECURE
├── backup/        Encrypted backup and restore, weekly auto-backup worker
├── reminders/     Alarm scheduling, notification actions, boot receiver
├── work/          OCR / PDF-text and trash-purge workers
├── widget/        Glance home-screen widget
├── tiles/         Quick Settings tiles
├── share/         Share-in importer, share-out (text, PDF, images)
├── pdf/ ocr/ audio/ drawing/   Platform helpers
└── ui/            Theme, components, and every screen
```
