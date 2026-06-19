# Mosaic Tech Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign KissLink app interface to mosaic tech style with dual mode (light/dark), pixelated transitions, and full app coverage.

**Architecture:** Replace current warm editorial design with cool-toned mosaic tile aesthetic. Core changes: color system (light/dark), mosaic background grid, pixelated animations, monospace typography. All UI components (buttons, cards, progress bars) converted to blocky/tile-based forms.

**Tech Stack:** Android Views + Jetpack Compose, Material Components, XML resources

---

## File Structure

| File | Action | Purpose |
|------|--------|---------|
| `res/values/colors.xml` | Modify | Add mosaic color palette |
| `res/values-night/colors.xml` | Create | Dark mode colors |
| `res/values/styles.xml` | Modify | Mosaic styles (buttons, cards, text) |
| `res/layout/activity_home.xml` | Modify | Main screen layout updates |
| `res/layout/sheet_history.xml` | Modify | History sheet styling |
| `res/layout/sheet_profile.xml` | Modify | Profile sheet styling |
| `res/layout/item_send.xml` | Modify | Send list item styling |
| `res/layout/item_history_file.xml` | Modify | History file item styling |
| `ui/home/BeamStageView.kt` | Modify | Mosaic animations & visuals |
| `ui/home/Anim.java` | Modify | Pixelated transition animations |
| `ui/home/HomeActivity.java` | Modify | Theme application |
| `ui/history/HistorySheet.java` | Modify | Theme application |
| `ui/profile/ProfileCardSheet.java` | Modify | Theme application |

---

### Task 1: Color System (Light/Dark)

**Covers:** [S1]

**Files:**
- Modify: `app/src/main/res/values/colors.xml:1-28`
- Create: `app/src/main/res/values-night/colors.xml`

- [ ] **Step 1: Add mosaic colors to light mode**

```xml
<!-- Add after line 13 in colors.xml -->
    <!-- ── Mosaic Tech Style ──────────────────────────────── -->
    <color name="mosaic_bg">#F0F2F5</color>
    <color name="mosaic_surface">#FFFFFF</color>
    <color name="mosaic_ink">#1A1D23</color>
    <color name="mosaic_muted">#6B7280</color>
    <color name="mosaic_grid">#E5E7EB</color>
    <color name="mosaic_accent">#3B82F6</color>
    <color name="mosaic_accent2">#8B5CF6</color>
    <color name="mosaic_tile1">#DBEAFE</color>
    <color name="mosaic_tile2">#EDE9FE</color>
    <color name="mosaic_tile3">#D1FAE5</color>
    <color name="mosaic_error">#EF4444</color>
    <color name="mosaic_success">#10B981</color>
    <color name="mosaic_gradient_start">#3B82F6</color>
    <color name="mosaic_gradient_end">#8B5CF6</color>
```

- [ ] **Step 2: Create dark mode colors file**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="mosaic_bg">#0F1117</color>
    <color name="mosaic_surface">#1A1D23</color>
    <color name="mosaic_ink">#F3F4F6</color>
    <color name="mosaic_muted">#9CA3AF</color>
    <color name="mosaic_grid">#2D3140</color>
    <color name="mosaic_accent">#60A5FA</color>
    <color name="mosaic_accent2">#A78BFA</color>
    <color name="mosaic_tile1">#1E3A5F</color>
    <color name="mosaic_tile2">#2D1F4E</color>
    <color name="mosaic_tile3">#0D3B2E</color>
    <color name="mosaic_error">#F87171</color>
    <color name="mosaic_success">#34D399</color>
    <color name="mosaic_gradient_start">#60A5FA</color>
    <color name="mosaic_gradient_end">#A78BFA</color>
</resources>
```

- [ ] **Step 3: Update old beam colors to reference mosaic**

```xml
<!-- Replace beam_bg, beam_ink etc. with mosaic equivalents -->
    <color name="beam_bg">@color/mosaic_bg</color>
    <color name="beam_surface">@color/mosaic_surface</color>
    <color name="beam_ink">@color/mosaic_ink</color>
    <color name="beam_muted">@color/mosaic_muted</color>
    <color name="beam_track">@color/mosaic_grid</color>
    <color name="beam_accent">@color/mosaic_accent</color>
    <color name="beam_accent_soft">#1A3B82F6</color>
    <color name="beam_on_accent">#FFFFFF</color>
```

- [ ] **Step 4: Verify colors compile**

Run: `cd app && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 2: Mosaic Styles

**Covers:** [S2]

**Files:**
- Modify: `app/src/main/res/values/styles.xml:1-75`

- [ ] **Step 1: Replace styles with mosaic versions**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Mosaic Tech Style -->
    
    <!-- Monospace label (uppercase, wide spacing) -->
    <style name="Beam.Label" parent="">
        <item name="android:textColor">@color/mosaic_muted</item>
        <item name="android:textSize">12sp</item>
        <item name="android:textFontWeight">600</item>
        <item name="android:letterSpacing">0.18</item>
        <item name="android:textAllCaps">true</item>
        <item name="android:fontFamily">monospace</item>
    </style>

    <!-- Headline (monospace, light weight) -->
    <style name="Beam.Headline" parent="">
        <item name="android:textColor">@color/mosaic_ink</item>
        <item name="android:textSize">34sp</item>
        <item name="android:textFontWeight">200</item>
        <item name="android:letterSpacing">-0.02</item>
        <item name="android:fontFamily">monospace</item>
    </style>

    <!-- Subtitle -->
    <style name="Beam.Sub" parent="">
        <item name="android:textColor">@color/mosaic_muted</item>
        <item name="android:textSize">14sp</item>
        <item name="android:textFontWeight">500</item>
        <item name="android:fontFamily">monospace</item>
    </style>

    <!-- Wordmark (top-left) -->
    <style name="Beam.Wordmark" parent="">
        <item name="android:textColor">@color/mosaic_ink</item>
        <item name="android:textSize">19sp</item>
        <item name="android:textFontWeight">700</item>
        <item name="android:letterSpacing">0.05</item>
        <item name="android:fontFamily">monospace</item>
    </style>

    <!-- Pick button (blocky pill) -->
    <style name="Beam.PickButton" parent="Widget.Material3.Button.TextButton">
        <item name="android:textFontWeight">600</item>
        <item name="android:textSize">14sp</item>
        <item name="android:textColor">@color/mosaic_ink</item>
        <item name="android:textAllCaps">false</item>
        <item name="android:fontFamily">monospace</item>
        <item name="backgroundTint">@android:color/transparent</item>
        <item name="strokeColor">@color/mosaic_grid</item>
        <item name="strokeWidth">1.5dp</item>
        <item name="cornerRadius">4dp</item>
        <item name="iconTint">@color/mosaic_ink</item>
        <item name="iconGravity">textStart</item>
        <item name="iconSize">18dp</item>
        <item name="rippleColor">@color/mosaic_accent_soft</item>
    </style>

    <!-- Avatar (square with small radius) -->
    <style name="Beam.Avatar.Circle" parent="">
        <item name="cornerFamily">rounded</item>
        <item name="cornerSize">8dp</item>
    </style>

    <!-- Thumbnail (square corners) -->
    <style name="Beam.Thumb.Rounded" parent="">
        <item name="cornerFamily">rounded</item>
        <item name="cornerSize">4dp</item>
    </style>

    <!-- Send button (gradient fill) -->
    <style name="Beam.SendButton" parent="Widget.Material3.Button">
        <item name="android:textFontWeight">700</item>
        <item name="android:textSize">16sp</item>
        <item name="android:textAllCaps">false</item>
        <item name="android:fontFamily">monospace</item>
        <item name="backgroundTint">@color/mosaic_accent</item>
        <item name="android:textColor">#FFFFFF</item>
        <item name="cornerRadius">4dp</item>
        <item name="iconTint">#FFFFFF</item>
        <item name="iconGravity">textStart</item>
        <item name="elevation">0dp</item>
    </style>
</resources>
```

- [ ] **Step 2: Verify styles compile**

Run: `cd app && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 3: Mosaic Background Canvas

**Covers:** [S2]

**Files:**
- Modify: `app/src/main/java/com/kisslink/ui/home/BeamStageView.kt`

- [ ] **Step 1: Add mosaic background drawing**

```kotlin
// Add after line 124 (before BeamStage composable)

private val TILE_SIZE = 12.dp
private val TILE_GAP = 1.dp
private val MOSAIC_COLORS = listOf(
    Color(0xFFDBEAFE), // tile1
    Color(0xFFEDE9FE), // tile2
    Color(0xFFD1FAE5), // tile3
)

@Composable
private fun MosaicBackground(
    modifier: Modifier = Modifier,
    alpha: Float = 0.5f
) {
    val infinite = rememberInfiniteTransition(label = "mosaic_bg")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing)),
        label = "phase"
    )
    
    Canvas(modifier = modifier) {
        val tileSize = TILE_SIZE.toPx()
        val gap = TILE_GAP.toPx()
        val step = tileSize + gap
        val cols = (size.width / step).toInt() + 1
        val rows = (size.height / step).toInt() + 1
        
        for (row in 0..rows) {
            for (col in 0..cols) {
                val x = col * step
                val y = row * step
                // Pseudo-random based on position
                val seed = (row * 31 + col * 17).toFloat()
                val colorIndex = ((seed * 7.3f).toInt() % 3).coerceIn(0, 2)
                val tileAlpha = ((Math.sin(seed + phase * 6.28f).toFloat() * 0.3f + 0.5f) * alpha)
                    .coerceIn(0.1f, 0.8f)
                
                drawRect(
                    color = MOSAIC_COLORS[colorIndex].copy(alpha = tileAlpha),
                    topLeft = Offset(x, y),
                    size = Size(tileSize, tileSize)
                )
            }
        }
    }
}
```

- [ ] **Step 2: Update BeamStage to use mosaic background**

```kotlin
// In BeamStage composable, add MosaicBackground before Canvas
// Find line 197-198 and update:

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Add mosaic background
        MosaicBackground(modifier = Modifier.fillMaxSize(), alpha = 0.3f)
        
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
```

- [ ] **Step 3: Verify build**

Run: `cd app && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 4: Pixelated Animations

**Covers:** [S4]

**Files:**
- Modify: `app/src/main/java/com/kisslink/ui/home/Anim.java`

- [ ] **Step 1: Add pixelated fade animation**

```java
// Add after revealFadeUp method (line 30)

/** 像素化淡入：方塊逐個出現效果。 */
static void pixelateReveal(View v, int durationMs) {
    if (v == null) return;
    v.setAlpha(0f);
    v.animate().cancel();
    
    // Scale from small blocks to full
    v.setScaleX(0.95f);
    v.setScaleY(0.95f);
    v.animate()
        .alpha(1f)
        .scaleX(1f)
        .scaleY(1f)
        .setDuration(durationMs > 0 ? durationMs : 300)
        .setInterpolator(new DecelerateInterpolator(1.8f))
        .start();
}

/** 像素化淡出：方塊逐個消失效果。 */
static void pixelateHide(View v, int durationMs) {
    if (v == null) return;
    v.animate().cancel();
    v.animate()
        .alpha(0f)
        .setScaleX(0.95f)
        .setScaleY(0.95f)
        .setDuration(durationMs > 0 ? durationMs : 200)
        .start();
}
```

- [ ] **Step 2: Verify build**

Run: `cd app && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 5: Main Activity Theme

**Covers:** [S5]

**Files:**
- Modify: `app/src/main/java/com/kisslink/ui/home/HomeActivity.java`

- [ ] **Step 1: Add mosaic background to root layout**

```java
// In bindViews() method, add after line 170:
    // Apply mosaic theme
    applyMosaicTheme();
```

- [ ] **Step 2: Add applyMosaicTheme method**

```java
// Add new method after bindViews():
    private void applyMosaicTheme() {
        View root = findViewById(R.id.root);
        // Set mosaic background
        root.setBackgroundColor(getColor(R.color.mosaic_bg));
    }
```

- [ ] **Step 3: Verify build**

Run: `cd app && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 6: Update Existing Drawables

**Covers:** [S2]

**Files:**
- Modify: `app/src/main/res/drawable/bg_chip.xml`
- Modify: `app/src/main/res/drawable/bg_avatar.xml`

- [ ] **Step 1: Update chip background**

```xml
<!-- Replace bg_chip.xml content -->
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/mosaic_surface" />
    <stroke
        android:width="1dp"
        android:color="@color/mosaic_grid" />
    <corners android:radius="4dp" />
</shape>
```

- [ ] **Step 2: Update avatar background**

```xml
<!-- Replace bg_avatar.xml content -->
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/mosaic_surface" />
    <corners android:radius="8dp" />
</shape>
```

- [ ] **Step 3: Verify build**

Run: `cd app && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 7: History Sheet Theme

**Covers:** [S6]

**Files:**
- Modify: `app/src/main/res/layout/sheet_history.xml`
- Modify: `app/src/main/res/layout/item_history_file.xml`

- [ ] **Step 1: Update history sheet background**

```xml
<!-- In sheet_history.xml, update root element background -->
android:background="@color/mosaic_surface"
```

- [ ] **Step 2: Update history item styling**

```xml
<!-- In item_history_file.xml, update card background -->
android:background="@color/mosaic_surface"
android:strokeColor="@color/mosaic_grid"
```

- [ ] **Step 3: Verify build**

Run: `cd app && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 8: Profile Sheet Theme

**Covers:** [S6]

**Files:**
- Modify: `app/src/main/res/layout/sheet_profile.xml`
- Modify: `app/src/main/res/layout/dialog_received_card.xml`

- [ ] **Step 1: Update profile sheet**

```xml
<!-- In sheet_profile.xml, update root background -->
android:background="@color/mosaic_surface"
```

- [ ] **Step 2: Update received card dialog**

```xml
<!-- In dialog_received_card.xml, update background -->
android:background="@color/mosaic_surface"
```

- [ ] **Step 3: Verify build**

Run: `cd app && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 9: Send List Item Theme

**Covers:** [S6]

**Files:**
- Modify: `app/src/main/res/layout/item_send.xml`

- [ ] **Step 1: Update send item styling**

```xml
<!-- In item_send.xml, update card background -->
android:background="@color/mosaic_surface"
android:strokeColor="@color/mosaic_grid"
android:cornerRadius="4dp"
```

- [ ] **Step 2: Verify build**

Run: `cd app && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 10: Final Verification

**Covers:** [S1, S2, S3, S4, S5, S6]

**Files:** All modified files

- [ ] **Step 1: Full clean build**

Run: `cd app && ./gradlew clean assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run lint check**

Run: `cd app && ./gradlew lint`
Expected: No new errors

- [ ] **Step 3: Visual verification checklist**

- [ ] Light mode: mosaic background visible
- [ ] Dark mode: dark background with colored tiles
- [ ] Buttons: square corners (4dp)
- [ ] Avatars: square with 8dp radius
- [ ] Text: monospace font
- [ ] Animations: pixelated transitions
