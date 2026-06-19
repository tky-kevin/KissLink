# 歷史紀錄 Sheet 對齊待傳項目 Sheet Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align the history record bottom sheet UI with the pending items bottom sheet — same drag handle, title style, padding, and corner radius.

**Architecture:** Modify `sheet_history.xml` to match the programmatic styling of `showSendSheet()` in HomeActivity.java. All changes are layout-only; no Java/Kotlin code changes needed.

**Tech Stack:** Android XML layouts, Material BottomSheetDialog/DialogFragment

---

### Task 1: Update sheet_history.xml

**Covers:** UI alignment (title, drag handle, padding)

**Files:**
- Modify: `app\src\main\res\layout\sheet_history.xml`

**Differences to fix (pending sheet as reference):**

| Element | History (current) | Pending (target) |
|---|---|---|
| Drag handle View | 36dp x 4dp, margin-bottom 14dp | **None** |
| Title style | `@style/Beam.Label`, marginStart 24dp, marginBottom 6dp | 18sp Bold, `beam_ink` color |
| Container padding | top 10dp, bottom 8dp | 20dp all, 8dp bottom |
| RecyclerView maxHeight | 520dp | None (wrap_content) |

- [ ] **Step 1: Remove the drag handle View**

Remove lines 10-15 (the `<View>` element with 36dp x 4dp).

- [ ] **Step 2: Update title styling**

Change the title `<TextView>` from:
```xml
<TextView
    android:id="@+id/tvSheetTitle"
    style="@style/Beam.Label"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_marginStart="24dp"
    android:layout_marginBottom="6dp"
    android:text="@string/history_title" />
```
To:
```xml
<TextView
    android:id="@+id/tvSheetTitle"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_marginBottom="10dp"
    android:textColor="@color/beam_ink"
    android:textSize="18sp"
    android:textStyle="bold"
    android:text="@string/history_title" />
```

- [ ] **Step 3: Update container padding**

Change the root `<LinearLayout>` padding from `android:paddingTop="10dp"` to `android:paddingStart="20dp"`, `android:paddingEnd="20dp"`, keep `android:paddingBottom="8dp"`, add `android:paddingTop="20dp"`.

- [ ] **Step 4: Remove RecyclerView maxHeight**

Remove `android:maxHeight="520dp"` from the RecyclerView.

- [ ] **Step 5: Verify final XML**

Read the file back and confirm all changes are correct.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/res/layout/sheet_history.xml
git commit -m "style: align history sheet UI with pending items sheet"
```
