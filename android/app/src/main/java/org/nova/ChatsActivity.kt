package org.nova

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Chat list: open, create, rename, export and delete conversations.
 */
class ChatsActivity : Activity() {

    private lateinit var listInner: LinearLayout
    private lateinit var settings: Settings

    private val bg = NovaTheme.bg
    private val surface = NovaTheme.pill
    private val accent = NovaTheme.accent
    private val textMain = NovaTheme.text
    private val textDim = NovaTheme.dim

    private var exportChat: Chat? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(0, dp(36), 0, dp(12))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        header.addView(Button(this).apply {
            isAllCaps = false
            background = null
            val d = getDrawable(R.drawable.ic_back)!!.mutate()
            d.colorFilter = android.graphics.PorterDuffColorFilter(
                NovaTheme.text, android.graphics.PorterDuff.Mode.SRC_IN)
            setCompoundDrawablesWithIntrinsicBounds(d, null, null, null)
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "Chats"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textMain)
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(smallButton("New", accent).apply {
            val d = getDrawable(R.drawable.ic_add)!!.mutate()
            d.colorFilter = android.graphics.PorterDuffColorFilter(
                accent, android.graphics.PorterDuff.Mode.SRC_IN)
            setCompoundDrawablesWithIntrinsicBounds(d, null, null, null)
            compoundDrawablePadding = dp(4)
            setOnClickListener {
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_NEW_CHAT, true))
                finish()
            }
        })
        root.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        root.addView(View(this).apply { setBackgroundColor(Color.parseColor("#1A2030")) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        listInner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(listInner)
        root.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(14); rightMargin = dp(14); topMargin = dp(14)
        })

        refresh()
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
    }

    private fun refresh() {
        listInner.removeAllViews()
        val chats = ChatStore.list(this)
        val currentId = settings.currentChatId

        if (chats.isEmpty()) {
            listInner.addView(TextView(this).apply {
                text = "No saved chats yet.\nEverything you talk about is kept on this phone only."
                setTextColor(textDim)
                textSize = 13f
                setPadding(0, dp(10), 0, dp(10))
            })
            return
        }

        val fmt = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
        for (chat in chats) {
            val active = chat.id == currentId
            val preview = chat.messages.lastOrNull { it.text.isNotBlank() }?.text
                ?.replace('\n', ' ')?.take(60) ?: "empty"

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(10), dp(4), dp(10))
                setOnClickListener {
                    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_CHAT_ID, chat.id))
                    finish()
                }
                setOnLongClickListener { chatMenu(chat); true }
            }
            val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            titleRow.addView(TextView(this).apply {
                text = (if (active) "● " else "") + chat.name
                setTextColor(if (active) accent else textMain)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            titleRow.addView(TextView(this).apply {
                text = fmt.format(Date(chat.updatedAt))
                setTextColor(textDim)
                textSize = 11f
            })
            row.addView(titleRow)
            row.addView(TextView(this).apply {
                text = "$preview  ·  ${chat.messages.size} messages"
                setTextColor(textDim)
                textSize = 12f
                setPadding(0, dp(2), 0, 0)
            })

            val sep = View(this).apply {
                setBackgroundColor(Color.parseColor("#1F2635"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(10) }
            }
            listInner.addView(row)
            listInner.addView(sep)
        }
    }

    private fun chatMenu(chat: Chat) {
        val options = arrayOf("Rename", "Export as text", "Delete")
        AlertDialog.Builder(this)
            .setTitle(chat.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> renameDialog(chat)
                    1 -> {
                        exportChat = chat
                        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TITLE,
                                chat.name.replace(Regex("[^A-Za-z0-9 ._-]"), "_") + ".txt")
                        }
                        startActivityForResult(intent, REQ_EXPORT)
                    }
                    2 -> AlertDialog.Builder(this)
                        .setMessage("Delete \"${chat.name}\"? This cannot be undone.")
                        .setPositiveButton("Delete") { _, _ ->
                            val wasCurrent = settings.currentChatId == chat.id
                            ChatStore.delete(this, chat.id)
                            if (wasCurrent) {
                                // deleted the open chat - return to a fresh one,
                                // otherwise it gets re-saved on the next message
                                settings.currentChatId = ""
                                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_NEW_CHAT, true))
                                finish()
                            } else {
                                refresh()
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .show()
    }

    private fun renameDialog(chat: Chat) {
        val edit = EditText(this).apply {
            setText(chat.name)
            setSelection(chat.name.length)
            setTextColor(textMain)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Rename chat")
            .setView(edit)
            .setPositiveButton("Save") { _, _ ->
                val name = edit.text.toString().trim()
                if (name.isNotBlank()) {
                    chat.name = name
                    ChatStore.save(this, chat)
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_EXPORT && resultCode == Activity.RESULT_OK) {
            val chat = exportChat ?: return
            val uri: Uri = data?.data ?: return
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(ChatStore.transcript(chat).toByteArray())
                }
                toast("Saved")
            } catch (e: Exception) {
                toast("Export failed: ${e.message}")
            }
            exportChat = null
        }
    }

    private fun smallButton(label: String, color: Int): Button = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setTextColor(color)
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), color)
            cornerRadius = dp(20).toFloat()
        }
        setPadding(dp(14), dp(6), dp(14), dp(6))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_NEW_CHAT = "new_chat"
        private const val REQ_EXPORT = 4244
    }
}
