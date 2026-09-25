package app.aino.mobile.feature.chat

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.EmojiSymbols
import androidx.compose.material.icons.outlined.Fastfood
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Clean-room Compose emoji keyboard modelled on the behaviour of Signal-Android's
 * inline emoji panel. No Signal code or art is used; glyphs come from system
 * emoji fonts (EmojiCompat).
 */

private fun e(s: String) = s.trim().split(Regex("\\s+"))

val SignalEmojiCategories: List<EmojiCategory> = listOf(
    EmojiCategory("Smileys & People", e("""
        😀 😃 😄 😁 😆 😅 😂 🤣 🥲 ☺️ 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😝 😜 🤪 🤨 🧐 🤓 😎 🥸 🤩 🥳
        😏 😒 😞 😔 😟 😕 🙁 ☹️ 😣 😖 😫 😩 🥺 😢 😭 😤 😠 😡 🤬 🤯 😳 🥵 🥶 😱 😨 😰 😥 😓 🫣 🤗 🫡 🤔
        🫢 🤭 🤫 🤥 😶 😐 😑 😬 🫠 🙄 😯 😦 😧 😮 😲 🥱 😴 🤤 😪 😵 🤐 🥴 🤢 🤮 🤧 😷 🤒 🤕 🤑 🤠 😈 👿
        👹 👺 🤡 💩 👻 💀 ☠️ 👽 👾 🤖 🎃 😺 😸 😹 😻 😼 😽 🙀 😿 😾
        👋 🤚 🖐️ ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🫰 🤟 🤘 🤙 👈 👉 👆 🖕 👇 ☝️ 🫵 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 🫶 👐 🤲
        🤝 🙏 ✍️ 💅 🤳 💪 🦾 🦵 🦶 👂 👃 🧠 👀 👁️ 👅 👄 💋
        👶 🧒 👦 👧 🧑 👱 👨 🧔 👩 🧓 👴 👵 🙍 🙎 🙅 🙆 💁 🙋 🧏 🙇 🤦 🤷 👮 🕵️ 💂 🥷 👷 🤴 👸 👳 👲 🧕
        🤵 👰 🤰 🤱 👼 🎅 🤶 🦸 🦹 🧙 🧚 🧛 🧜 🧝 🧞 🧟 💆 💇 🚶 🧍 🧎 🏃 💃 🕺 👯 🧖 🧗 🤸 🏌️ 🏇 ⛷️ 🏂
        👩‍⚕️ 👨‍🍳 👩‍🎓 👨‍💻 👩‍🔬 🧑‍🚀 👨‍🚒 👩‍🎨 👫 👭 👬 💏 💑 👪 🗣️ 👤 👥
        ❤️ 🩷 🧡 💛 💚 💙 🩵 💜 🤎 🖤 🩶 🤍 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟
    """)),
    EmojiCategory("Animals & Nature", e("""
        🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐻‍❄️ 🐨 🐯 🦁 🐮 🐷 🐽 🐸 🐵 🙈 🙉 🙊 🐒 🐔 🐧 🐦 🐤 🐣 🐥 🦆 🦅 🦉 🦇 🐺
        🐗 🐴 🦄 🐝 🪱 🐛 🦋 🐌 🐞 🐜 🪰 🪲 🪳 🦟 🦗 🕷️ 🕸️ 🦂 🐢 🐍 🦎 🦖 🦕 🐙 🦑 🦐 🦞 🦀 🐡 🐠 🐟 🐬
        🐳 🐋 🦈 🦭 🐊 🐅 🐆 🦓 🦍 🦧 🦣 🐘 🦛 🦏 🐪 🐫 🦒 🦘 🦬 🐃 🐂 🐄 🐎 🐖 🐏 🐑 🦙 🐐 🦌 🐕 🐩 🦮
        🐈 🐓 🦃 🦤 🦚 🦜 🦢 🦩 🕊️ 🐇 🦝 🦨 🦡 🦫 🦦 🦥 🐁 🐀 🐿️ 🦔 🐾 🐉 🐲
        🌵 🎄 🌲 🌳 🌴 🪵 🌱 🌿 ☘️ 🍀 🎍 🪴 🎋 🍃 🍂 🍁 🍄 🐚 🪨 🌾 💐 🌷 🌹 🥀 🌺 🌸 🌼 🌻
        🌞 🌝 🌛 🌜 🌚 🌕 🌖 🌗 🌘 🌑 🌒 🌓 🌔 🌙 🌎 🌍 🌏 🪐 💫 ⭐ 🌟 ✨ ⚡ ☄️ 💥 🔥 🌪️ 🌈 ☀️ 🌤️ ⛅ 🌥️
        ☁️ 🌦️ 🌧️ ⛈️ 🌩️ 🌨️ ❄️ ☃️ ⛄ 🌬️ 💨 💧 💦 ☔ ☂️ 🌊 🌫️
    """)),
    EmojiCategory("Food & Drink", e("""
        🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶️ 🫑 🌽 🥕 🫒 🧄 🧅 🥔 🍠
        🥐 🥯 🍞 🥖 🥨 🧀 🥚 🍳 🧈 🥞 🧇 🥓 🥩 🍗 🍖 🦴 🌭 🍔 🍟 🍕 🫓 🥪 🥙 🧆 🌮 🌯 🫔 🥗 🥘 🫕 🥫 🍝
        🍜 🍲 🍛 🍣 🍱 🥟 🦪 🍤 🍙 🍚 🍘 🍥 🥠 🥮 🍢 🍡 🍧 🍨 🍦 🥧 🧁 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪 🌰 🥜
        🍯 🥛 🍼 🫖 ☕ 🍵 🧃 🥤 🧋 🍶 🍺 🍻 🥂 🍷 🥃 🍸 🍹 🧉 🍾 🧊 🥄 🍴 🍽️ 🥣 🥡 🥢 🧂
    """)),
    EmojiCategory("Activities", e("""
        ⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🪀 🏓 🏸 🏒 🏑 🥍 🏏 🪃 🥅 ⛳ 🪁 🏹 🎣 🤿 🥊 🥋 🎽 🛹 🛼 🛷 ⛸️ 🥌
        🎿 🏋️ 🤼 🤺 🤾 🧘 🏄 🏊 🤽 🚣 🚴 🚵 🎖️ 🏆 🥇 🥈 🥉 🏅 🎗️ 🏵️ 🎫 🎟️ 🎪 🤹 🎭 🩰 🎨 🎬 🎤 🎧 🎼 🎹
        🥁 🪘 🎷 🎺 🪗 🎸 🪕 🎻 🎲 ♟️ 🎯 🎳 🎮 🎰 🧩 🎉 🎊 🎈 🎁 🎀 🎆 🎇 🧨 🎃 🎄 🎋 🎍 🎎 🎏 🎐 🎑 🧧
    """)),
    EmojiCategory("Travel & Places", e("""
        🚗 🚕 🚙 🚌 🚎 🏎️ 🚓 🚑 🚒 🚐 🛻 🚚 🚛 🚜 🦯 🦽 🦼 🛴 🚲 🛵 🏍️ 🛺 🚨 🚔 🚍 🚘 🚖 🚡 🚠 🚟 🚃 🚋
        🚞 🚝 🚄 🚅 🚈 🚂 🚆 🚇 🚊 🚉 ✈️ 🛫 🛬 🛩️ 💺 🛰️ 🚀 🛸 🚁 🛶 ⛵ 🚤 🛥️ 🛳️ ⛴️ 🚢 ⚓ 🪝 ⛽ 🚧 🚦 🚥
        🚏 🗺️ 🗿 🗽 🗼 🏰 🏯 🏟️ 🎡 🎢 🎠 ⛲ ⛱️ 🏖️ 🏝️ 🏜️ 🌋 ⛰️ 🏔️ 🗻 🏕️ ⛺ 🛖 🏠 🏡 🏘️ 🏚️ 🏗️ 🏭 🏢 🏬 🏣
        🏤 🏥 🏦 🏨 🏪 🏫 🏩 💒 🏛️ ⛪ 🕌 🕍 🛕 🕋 ⛩️ 🛤️ 🛣️ 🗾 🎑 🏞️ 🌅 🌄 🌠 🎇 🎆 🌇 🌆 🏙️ 🌃 🌌 🌉 🌁
    """)),
    EmojiCategory("Objects", e("""
        ⌚ 📱 📲 💻 ⌨️ 🖥️ 🖨️ 🖱️ 🖲️ 🕹️ 🗜️ 💽 💾 💿 📀 📼 📷 📸 📹 🎥 📽️ 🎞️ 📞 ☎️ 📟 📠 📺 📻 🎙️ 🎚️ 🎛️ 🧭
        ⏱️ ⏲️ ⏰ 🕰️ ⌛ ⏳ 📡 🔋 🪫 🔌 💡 🔦 🕯️ 🪔 🧯 🛢️ 💸 💵 💴 💶 💷 🪙 💰 💳 💎 ⚖️ 🪜 🧰 🪛 🔧 🔨 ⚒️
        🛠️ ⛏️ 🪚 🔩 ⚙️ 🪤 🧱 ⛓️ 🧲 🔫 💣 🧨 🪓 🔪 🗡️ ⚔️ 🛡️ 🚬 ⚰️ 🪦 ⚱️ 🏺 🔮 📿 🧿 💈 ⚗️ 🔭 🔬 🕳️ 🩹 🩺
        💊 💉 🩸 🧬 🦠 🧫 🧪 🌡️ 🧹 🪠 🧺 🧻 🚽 🚰 🚿 🛁 🧼 🪥 🪒 🧽 🪣 🧴 🛎️ 🔑 🗝️ 🚪 🪑 🛋️ 🛏️ 🧸 🪆 🖼️
        🪞 🪟 🛍️ 🛒 🎁 🎈 🎏 🎀 🪄 🪅 🎊 🎉 🎎 🏮 🎐 🧧 ✉️ 📩 📨 📧 💌 📥 📤 📦 🏷️ 🪧 📪 📫 📬 📭 📮 📯
        📜 📃 📄 📑 🧾 📊 📈 📉 🗒️ 🗓️ 📆 📅 🗑️ 📇 🗃️ 🗳️ 🗄️ 📋 📁 📂 🗂️ 🗞️ 📰 📓 📔 📒 📕 📗 📘 📙 📚 📖
        🔖 🧷 🔗 📎 🖇️ 📐 📏 🧮 📌 📍 ✂️ 🖊️ 🖋️ ✒️ 🖌️ 🖍️ 📝 ✏️ 🔍 🔎 🔏 🔐 🔒 🔓 👓 🕶️ 🥽 👔 👕 👖 👗 👙
    """)),
    EmojiCategory("Symbols", e("""
        💯 💢 💬 👁️‍🗨️ 🗨️ 🗯️ 💭 💤 ☮️ ✝️ ☪️ 🕉️ ☸️ ✡️ 🔯 🕎 ☯️ ☦️ 🛐 ⛎ ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓
        🆔 ⚛️ 🉑 ☢️ ☣️ 📴 📳 🈶 🈚 🈸 🈺 🈷️ ✴️ 🆚 💮 🉐 ㊙️ ㊗️ 🈴 🈵 🈹 🈲 🅰️ 🅱️ 🆎 🆑 🅾️ 🆘 ❌ ⭕ 🛑 ⛔
        📛 🚫 🚷 🚯 🚳 🚱 🔞 📵 🚭 ❗ ❕ ❓ ❔ ‼️ ⁉️ 🔅 🔆 〽️ ⚠️ 🚸 🔱 ⚜️ 🔰 ♻️ ✅ 🈯 💹 ❇️ ✳️ ❎ 🌐 💠
        Ⓜ️ 🌀 🏧 🚾 ♿ 🅿️ 🛗 🚹 🚺 🚼 ⚧️ 🚻 🚮 🎦 📶 🈁 🔣 ℹ️ 🔤 🔡 🔠 🆖 🆗 🆙 🆒 🆕 🆓 0️⃣ 1️⃣ 2️⃣ 3️⃣ 4️⃣
        5️⃣ 6️⃣ 7️⃣ 8️⃣ 9️⃣ 🔟 🔢 #️⃣ *️⃣ ⏏️ ▶️ ⏸️ ⏯️ ⏹️ ⏺️ ⏭️ ⏮️ ⏩ ⏪ ⏫ ⏬ ◀️ 🔼 🔽 ➡️ ⬅️ ⬆️ ⬇️ ↗️ ↘️ ↙️ ↖️
        ↕️ ↔️ ↪️ ↩️ ⤴️ ⤵️ 🔀 🔁 🔂 🔄 🔃 🎵 🎶 ➕ ➖ ➗ ✖️ 🟰 ♾️ 💲 💱 ™️ ©️ ®️ 〰️ ➰ ➿ 🔚 🔙 🔛 🔝 🔜
        ✔️ ☑️ 🔘 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟤 🔺 🔻 🔸 🔹 🔶 🔷 🔳 🔲 ▪️ ▫️ ◾ ◽ ◼️ ◻️ 🟥 🟧 🟨 🟩 🟦 🟪 ⬛ ⬜
        🔈 🔇 🔉 🔊 🔔 🔕 📣 📢 ♠️ ♣️ ♥️ ♦️ 🃏 🎴 🀄 🕐 🕑 🕒 🕓 🕔 🕕 🕖 🕗 🕘 🕙 🕚 🕛
    """)),
    EmojiCategory("Flags", e("""
        🏁 🚩 🎌 🏴 🏳️ 🏳️‍🌈 🏳️‍⚧️ 🏴‍☠️ 🇺🇳 🇦🇷 🇦🇺 🇦🇹 🇧🇪 🇧🇩 🇧🇷 🇨🇦 🇨🇱 🇨🇳 🇨🇴 🇭🇷 🇨🇿 🇩🇰 🇪🇬 🇪🇪 🇫🇮 🇫🇷 🇩🇪 🇬🇷 🇭🇰 🇭🇺 🇮🇸 🇮🇳
        🇮🇩 🇮🇷 🇮🇶 🇮🇪 🇮🇱 🇮🇹 🇯🇵 🇯🇴 🇰🇪 🇰🇷 🇱🇻 🇱🇧 🇱🇹 🇱🇺 🇲🇾 🇲🇽 🇲🇦 🇳🇵 🇳🇱 🇳🇿 🇳🇬 🇳🇴 🇵🇰 🇵🇪 🇵🇭 🇵🇱 🇵🇹 🇶🇦 🇷🇴 🇷🇺 🇸🇦 🇷🇸
        🇸🇬 🇸🇰 🇸🇮 🇿🇦 🇪🇸 🇱🇰 🇸🇪 🇨🇭 🇹🇼 🇹🇭 🇹🇷 🇺🇦 🇦🇪 🇬🇧 🇺🇸 🇺🇾 🇻🇳 🇪🇺
    """)),
)

/** Search keywords for the most common emoji. */
val EmojiKeywords: Map<String, String> = mapOf(
    "😀" to "grin smile happy", "😃" to "smile happy joy", "😄" to "smile happy laugh", "😁" to "grin beam teeth",
    "😆" to "laugh squint haha", "😅" to "sweat smile nervous", "😂" to "laugh joy tears lol", "🤣" to "rofl rolling laugh lol",
    "🥲" to "smile tear grateful", "😊" to "blush smile happy", "😇" to "angel halo innocent", "🙂" to "slight smile",
    "🙃" to "upside down silly", "😉" to "wink", "😌" to "relieved calm", "😍" to "heart eyes love crush",
    "🥰" to "love hearts adore", "😘" to "kiss blow love", "😋" to "yum delicious tongue", "😛" to "tongue playful",
    "😜" to "wink tongue crazy", "🤪" to "zany crazy goofy", "🤨" to "raised eyebrow suspicious", "🧐" to "monocle inspect",
    "🤓" to "nerd geek glasses", "😎" to "cool sunglasses", "🤩" to "star struck wow", "🥳" to "party celebrate birthday",
    "😏" to "smirk", "😒" to "unamused meh", "😞" to "disappointed sad", "😔" to "pensive sad", "😟" to "worried",
    "😕" to "confused", "🙁" to "frown sad", "😣" to "persevere struggle", "😫" to "tired weary", "😩" to "weary tired",
    "🥺" to "pleading puppy eyes please", "😢" to "cry sad tear", "😭" to "sob cry loud", "😤" to "triumph huff angry",
    "😠" to "angry mad", "😡" to "rage angry mad pout", "🤬" to "cursing swear angry", "🤯" to "mind blown exploding",
    "😳" to "flushed embarrassed", "🥵" to "hot heat", "🥶" to "cold freezing", "😱" to "scream fear shock",
    "😨" to "fearful scared", "😰" to "anxious sweat", "😓" to "downcast sweat", "🤗" to "hug hugging",
    "🫡" to "salute respect", "🤔" to "thinking hmm", "🤭" to "giggle oops hand", "🤫" to "shush quiet secret",
    "😶" to "no mouth speechless", "😐" to "neutral meh", "😑" to "expressionless", "😬" to "grimace awkward",
    "🫠" to "melting", "🙄" to "eye roll", "😮" to "open mouth wow surprise", "😲" to "astonished shock",
    "🥱" to "yawn tired bored", "😴" to "sleep tired zzz", "🤤" to "drool", "😵" to "dizzy", "🤐" to "zipper mouth secret",
    "🤢" to "nausea sick", "🤮" to "vomit sick", "🤧" to "sneeze sick", "😷" to "mask sick", "🤒" to "fever sick thermometer",
    "🤑" to "money rich", "🤠" to "cowboy", "😈" to "devil smiling evil", "🤡" to "clown", "💩" to "poop",
    "👻" to "ghost halloween", "💀" to "skull dead", "👽" to "alien", "🤖" to "robot", "😺" to "cat smile",
    "👋" to "wave hello hi bye", "✋" to "raised hand stop high five", "👌" to "ok okay perfect", "✌️" to "peace victory",
    "🤞" to "fingers crossed luck", "🤟" to "love you gesture", "🤘" to "rock horns metal", "🤙" to "call me shaka",
    "👈" to "point left", "👉" to "point right", "👆" to "point up", "👇" to "point down", "☝️" to "index up one",
    "👍" to "thumbs up like yes ok good", "👎" to "thumbs down dislike no bad", "✊" to "fist raised", "👊" to "punch fist bump",
    "👏" to "clap applause bravo", "🙌" to "raise hands hooray praise", "🫶" to "heart hands love", "🤝" to "handshake deal agree",
    "🙏" to "pray please thanks folded hands", "💪" to "muscle strong flex biceps", "👀" to "eyes look see", "🧠" to "brain smart",
    "💋" to "kiss lips", "👶" to "baby", "🤦" to "facepalm", "🤷" to "shrug dunno", "🙋" to "raise hand", "🙇" to "bow",
    "💃" to "dance woman", "🕺" to "dance man", "🏃" to "run running", "🚶" to "walk walking",
    "❤️" to "red heart love", "🧡" to "orange heart", "💛" to "yellow heart", "💚" to "green heart", "💙" to "blue heart",
    "💜" to "purple heart", "🖤" to "black heart", "🤍" to "white heart", "💔" to "broken heart", "💕" to "two hearts love",
    "💖" to "sparkling heart", "💘" to "cupid heart arrow",
    "🐶" to "dog puppy", "🐱" to "cat kitten", "🐭" to "mouse", "🐰" to "rabbit bunny", "🦊" to "fox", "🐻" to "bear",
    "🐼" to "panda", "🐯" to "tiger", "🦁" to "lion", "🐮" to "cow", "🐷" to "pig", "🐸" to "frog", "🐵" to "monkey",
    "🙈" to "see no evil monkey", "🐔" to "chicken", "🐧" to "penguin", "🦄" to "unicorn", "🐝" to "bee", "🦋" to "butterfly",
    "🐢" to "turtle", "🐍" to "snake", "🐙" to "octopus", "🐬" to "dolphin", "🐳" to "whale", "🦈" to "shark", "🐘" to "elephant",
    "🌹" to "rose flower", "🌸" to "cherry blossom flower", "🌻" to "sunflower", "🌳" to "tree", "🍀" to "clover luck",
    "🌞" to "sun face", "🌙" to "moon night", "⭐" to "star", "🌟" to "glowing star", "✨" to "sparkles magic",
    "⚡" to "lightning zap", "🔥" to "fire lit hot flame", "🌈" to "rainbow", "☀️" to "sun sunny", "❄️" to "snow snowflake cold",
    "💧" to "droplet water", "🌊" to "wave ocean sea",
    "🍎" to "apple", "🍌" to "banana", "🍓" to "strawberry", "🍉" to "watermelon", "🍕" to "pizza", "🍔" to "burger hamburger",
    "🍟" to "fries", "🌮" to "taco", "🍣" to "sushi", "🍜" to "noodles ramen", "🍿" to "popcorn", "🍩" to "donut",
    "🍪" to "cookie", "🎂" to "cake birthday", "🍰" to "cake slice", "☕" to "coffee tea hot", "🍺" to "beer", "🍻" to "beers cheers",
    "🥂" to "cheers champagne toast", "🍷" to "wine", "🍾" to "champagne celebrate",
    "⚽" to "soccer football ball", "🏀" to "basketball", "🏆" to "trophy win", "🥇" to "gold medal first", "🎮" to "game controller video",
    "🎯" to "target bullseye dart", "🎉" to "party popper tada celebrate", "🎊" to "confetti", "🎈" to "balloon", "🎁" to "gift present",
    "🎵" to "music note", "🎶" to "music notes", "🎸" to "guitar",
    "🚗" to "car", "✈️" to "airplane plane flight travel", "🚀" to "rocket launch ship", "🏠" to "house home", "🏖️" to "beach",
    "📱" to "phone mobile", "💻" to "laptop computer", "💡" to "idea bulb light", "📷" to "camera photo", "💰" to "money bag",
    "💸" to "money flying", "🔑" to "key", "🔒" to "lock", "📌" to "pin pushpin", "📎" to "paperclip", "✏️" to "pencil",
    "📝" to "memo note write", "📅" to "calendar date", "⏰" to "alarm clock", "🔔" to "bell notification",
    "💯" to "hundred perfect 100", "✅" to "check done yes", "❌" to "cross no wrong x", "❓" to "question", "❗" to "exclamation",
    "⚠️" to "warning", "💤" to "sleep zzz", "💬" to "speech bubble chat", "➕" to "plus add", "✔️" to "check mark",
    "🏁" to "checkered flag finish", "🚩" to "red flag", "🏳️‍🌈" to "rainbow flag pride",
)

/** Pure recents list helper. */
object EmojiRecents {
    fun push(recents: List<String>, emoji: String, max: Int = 32): List<String> =
        (listOf(emoji) + recents.filter { it != emoji }).take(max)
}

/** Fitzpatrick skin tone support for a small set of hand/person emoji. */
object EmojiSkinTones {
    private val supported: Set<String> = e("""
        👋 🤚 🖐 ✋ 🖖 👌 🤌 🤏 ✌ 🤞 🫰 🤟 🤘 🤙 👈 👉 👆 🖕 👇 ☝ 🫵 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 🫶 👐 🤲 🙏 ✍ 💅 🤳 💪
        👂 👃 👶 🧒 👦 👧 🧑 👨 👩 🧓 👴 👵 🙋 🙇 🤦 🤷 💁 🙅 🙆 👮 👷 🤴 👸 🎅 💃 🕺 🏃 🚶
    """).toSet()

    private fun base(emoji: String) = emoji.replace("\uFE0F", "")

    fun supports(emoji: String): Boolean = base(emoji) in supported

    fun variants(emoji: String): List<String> {
        if (!supports(emoji)) return emptyList()
        val b = base(emoji)
        return (0x1F3FB..0x1F3FF).map { b + String(Character.toChars(it)) }
    }
}

private const val PREFS = "signal_emoji_recents"
private const val PREFS_KEY = "recents"

private fun loadRecents(ctx: Context): List<String> =
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREFS_KEY, null)
        ?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()

private fun saveRecents(ctx: Context, recents: List<String>) {
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREFS_KEY, recents.joinToString(" ")).apply()
}

private val categoryIcons: List<ImageVector> = listOf(
    Icons.Outlined.EmojiEmotions, Icons.Outlined.Pets, Icons.Outlined.Fastfood, Icons.Outlined.SportsSoccer,
    Icons.Outlined.DirectionsCar, Icons.Outlined.Lightbulb, Icons.Outlined.EmojiSymbols, Icons.Outlined.Flag,
)

private sealed interface GridEntry {
    data class Header(val title: String, val section: Int) : GridEntry
    data class Cell(val emoji: String, val section: Int) : GridEntry
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SignalEmojiKeyboard(
    onEmoji: (String) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 300.dp,
) {
    val colors = signalColors
    val context = LocalContext.current
    var recents by remember { mutableStateOf(loadRecents(context)) }
    var query by remember { mutableStateOf("") }
    var skinPopupFor by remember { mutableStateOf<String?>(null) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    // Sections: (title, icon, emojis). Recents only when non-empty.
    val sections = remember(recents) {
        buildList {
            if (recents.isNotEmpty()) add(Triple("Recents", Icons.Outlined.AccessTime, recents))
            SignalEmojiCategories.forEachIndexed { i, c -> add(Triple(c.name, categoryIcons[i], c.emojis)) }
        }
    }
    val entries: List<GridEntry> = remember(sections, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            sections.flatMapIndexed { i, (title, _, list) -> listOf(GridEntry.Header(title, i)) + list.map { GridEntry.Cell(it, i) } }
        } else {
            val matches = SignalEmojiCategories.flatMap { it.emojis }.distinct()
                .filter { em -> EmojiKeywords[em]?.split(' ')?.any { it.startsWith(q) } == true }
            listOf(GridEntry.Header(if (matches.isEmpty()) "No emoji found" else "Search results", -1)) +
                matches.map { GridEntry.Cell(it, -1) }
        }
    }
    val sectionStarts = remember(entries) {
        entries.withIndex().filter { it.value is GridEntry.Header }.associate { (it.value as GridEntry.Header).section to it.index }
    }
    val activeSection by remember(entries) {
        derivedStateOf {
            when (val entry = entries.getOrNull(gridState.firstVisibleItemIndex)) {
                is GridEntry.Header -> entry.section
                is GridEntry.Cell -> entry.section
                null -> 0
            }
        }
    }

    fun pick(emoji: String) {
        onEmoji(emoji)
        recents = EmojiRecents.push(recents, emoji).also { saveRecents(context, it) }
        skinPopupFor = null
    }

    Column(modifier.fillMaxWidth().height(height).background(colors.surface)) {
        // Search pill
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().height(40.dp)
                .clip(RoundedCornerShape(20.dp)).background(colors.searchPill).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, null, Modifier.size(20.dp), tint = colors.textSecondary)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search emoji", color = colors.textSecondary, fontSize = 15.sp)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = colors.text, fontSize = 15.sp),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search emoji" },
                )
            }
            if (query.isNotEmpty()) {
                Icon(
                    Icons.Outlined.Close, "Clear search",
                    Modifier.size(20.dp).clip(CircleShape).combinedClickable(onClick = { query = "" }),
                    tint = colors.textSecondary,
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(SignalDimens.emojiCell),
            state = gridState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp),
        ) {
            items(
                count = entries.size,
                span = { i -> if (entries[i] is GridEntry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                contentType = { i -> entries[i]::class },
            ) { i ->
                when (val entry = entries[i]) {
                    is GridEntry.Header -> Text(
                        entry.title,
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
                    )
                    is GridEntry.Cell -> Box(
                        Modifier.size(SignalDimens.emojiCell)
                            .clip(RoundedCornerShape(8.dp))
                            .semantics { contentDescription = entry.emoji }
                            .combinedClickable(
                                role = Role.Button,
                                onClick = { pick(entry.emoji) },
                                onLongClick = { if (EmojiSkinTones.supports(entry.emoji)) skinPopupFor = entry.emoji },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(entry.emoji, fontSize = 30.sp)
                        if (skinPopupFor == entry.emoji) {
                            Popup(
                                alignment = Alignment.TopCenter,
                                offset = IntOffset(0, -150),
                                onDismissRequest = { skinPopupFor = null },
                                properties = PopupProperties(focusable = true),
                            ) {
                                LazyRow(
                                    Modifier.clip(RoundedCornerShape(24.dp)).background(colors.background).padding(4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    val options = listOf(entry.emoji) + EmojiSkinTones.variants(entry.emoji)
                                    items(options.size) { k ->
                                        Text(
                                            options[k], fontSize = 30.sp,
                                            modifier = Modifier.size(SignalDimens.emojiCell).clip(CircleShape)
                                                .combinedClickable(onClick = { pick(options[k]) }).wrapContentSize(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bottom bar: category tabs + backspace
        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                sections.forEachIndexed { i, (title, icon, _) ->
                    val active = query.isEmpty() && activeSection == i
                    Box(
                        Modifier.size(36.dp).clip(CircleShape)
                            .background(if (active) colors.searchPill else colors.surface)
                            .combinedClickable(role = Role.Tab, onClick = {
                                query = ""
                                scope.launch { gridState.scrollToItem(sectionStarts[i] ?: 0) }
                            })
                            .semantics { contentDescription = title },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon, null, Modifier.size(20.dp), tint = if (active) colors.primary else colors.textSecondary)
                    }
                }
            }
            BackspaceButton(onBackspace, colors.textSecondary)
        }
    }
}

@Composable
private fun BackspaceButton(onBackspace: () -> Unit, tint: androidx.compose.ui.graphics.Color) {
    val current by rememberUpdatedState(onBackspace)
    val scope = rememberCoroutineScope()
    IconButton(onClick = {}, modifier = Modifier.pointerInput(Unit) {
        detectTapGestures(onPress = {
            current()
            val repeat = scope.launch {
                delay(400)
                while (true) { current(); delay(60) }
            }
            tryAwaitRelease()
            repeat.cancel()
        })
    }) {
        Icon(Icons.AutoMirrored.Outlined.Backspace, "Backspace", tint = tint)
    }
}
