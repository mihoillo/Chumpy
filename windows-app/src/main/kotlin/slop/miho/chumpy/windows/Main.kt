package slop.miho.chumpy.windows

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.io.File
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

private enum class ConnectionMode(val label: String) {
    SYSTEM_PROXY("Системный прокси"), TUN("TUN (виртуальный адаптер)")
}
fun main() = SwingUtilities.invokeLater {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    ChumpyWindow().isVisible = true
}
private class ChumpyWindow : JFrame("Chumpy — Windows") {
    private val home = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "Chumpy")
    private val profilesFile = File(home, "profiles.txt")
    private val subscriptionsFile = File(home, "subscriptions.txt")
    private val subscriptions = mutableListOf<String>()
    private val subscriptionImporter = SubscriptionImporter()
    private val profiles = DefaultListModel<String>()
    private val profileList = JList(profiles).apply {
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
                val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                val raw = value as? String
                val profile = raw?.let { LinkParsers.parse(it) }
                label.text = if (profile == null) raw.orEmpty() else
                    "${profile.remarks.ifBlank { profile.server }}  •  ${profile.server}:${profile.serverPort}  •  ${profile.type.name}"
                return label
            }
        }
    }
    private val mode = JComboBox(ConnectionMode.entries.toTypedArray())
    private val enginePath = JTextField(30)
    private val configPath = JTextField(30)
    private val status = JLabel("Отключено", SwingConstants.CENTER)
    private val engine = ManagedEngine()
    private val proxy = WindowsProxyController(home)

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) { disconnect(); dispose() }
        })
        minimumSize = Dimension(760, 540)
        size = Dimension(900, 650)
        setLocationRelativeTo(null)
        home.mkdirs()
        if (profilesFile.isFile) profilesFile.readLines().filter { it.isNotBlank() }.forEach(profiles::addElement)
        if (subscriptionsFile.isFile) subscriptions.addAll(subscriptionsFile.readLines().filter { it.isNotBlank() })

        val root = JPanel(BorderLayout(12, 12)).apply { border = BorderFactory.createEmptyBorder(18, 18, 18, 18) }
        root.add(JLabel("CHUMPY", SwingConstants.LEFT).apply { font = Font("Segoe UI", Font.BOLD, 28) }, BorderLayout.NORTH)
        val center = JPanel(BorderLayout(10, 10))
        val profilePanel = JPanel(BorderLayout(6, 6)).apply {
            border = BorderFactory.createTitledBorder("Профили и ссылки")
            add(JScrollPane(profileList), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JButton("Вставить ссылку").apply { addActionListener { pasteLink() } })
                add(JButton("Импортировать ссылки…").apply { addActionListener { importLinks() } })
                add(JButton("Импортировать QR-изображение…").apply { addActionListener { importQrImage() } })
                add(JButton("Добавить подписку").apply { addActionListener { addSubscription() } })
                add(JButton("Обновить подписки").apply { addActionListener { refreshSubscriptions() } })
                add(JButton("Создать Xray-конфиг").apply { addActionListener { generateConfig() } })
                add(JButton("Создать TUN-конфиг").apply { addActionListener { generateTunConfig() } })
                add(JButton("Удалить").apply { addActionListener { profileList.selectedIndices.sortedDescending().forEach(profiles::remove); saveProfiles() } })
            }, BorderLayout.SOUTH)
        }
        center.add(profilePanel, BorderLayout.CENTER)
        center.add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createTitledBorder("Подключение")
            add(JLabel("Режим")); add(mode)
            add(JLabel("Движок: xray.exe для прокси или sing-box.exe для TUN"))
            add(pathRow(enginePath, "exe"))
            add(JLabel("JSON-конфигурация выбранного движка")); add(pathRow(configPath, "json"))
            add(JLabel("TUN требует Wintun и запуск от имени администратора."))
        }, BorderLayout.SOUTH)
        root.add(center, BorderLayout.CENTER)
        root.add(JPanel(BorderLayout(8, 8)).apply {
            status.font = Font("Segoe UI", Font.PLAIN, 15)
            add(status, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                add(JButton("Отключить").apply { addActionListener { disconnect() } })
                add(JButton("Подключить").apply { addActionListener { connect() } })
            }, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        contentPane = root
    }

    private fun pathRow(field: JTextField, ext: String) = JPanel(BorderLayout(6, 0)).apply {
        add(field, BorderLayout.CENTER)
        add(JButton("Выбрать…").apply {
            addActionListener {
                val chooser = JFileChooser()
                chooser.fileFilter = FileNameExtensionFilter("$ext files", ext)
                if (chooser.showOpenDialog(this@ChumpyWindow) == JFileChooser.APPROVE_OPTION) field.text = chooser.selectedFile.absolutePath
            }
        }, BorderLayout.EAST)
    }
    private fun addSubscription() {
        val url = JOptionPane.showInputDialog(this, "URL подписки:")?.trim().orEmpty()
        if (url.isBlank()) return
        if (!url.startsWith("https://") && !url.startsWith("http://")) { JOptionPane.showMessageDialog(this, "Нужна ссылка HTTP(S)."); return }
        if (url !in subscriptions) subscriptions.add(url)
        subscriptionsFile.writeText(subscriptions.joinToString("\n"))
        refreshSubscriptions()
    }
    private fun refreshSubscriptions() {
        if (subscriptions.isEmpty()) { JOptionPane.showMessageDialog(this, "Сначала добавьте URL подписки."); return }
        status.text = "Обновление подписок…"
        Thread({
            var imported = 0
            val errors = mutableListOf<String>()
            subscriptions.forEach { url ->
                try {
                    val links = subscriptionImporter.fetch(url)
                    SwingUtilities.invokeLater {
                        links.forEach { link -> if ((0 until profiles.size()).none { profiles.getElementAt(it) == link }) profiles.addElement(link) }
                    }
                    imported += links.size
                } catch (e: Exception) { errors.add(url + ": " + (e.message ?: "ошибка")) }
            }
            SwingUtilities.invokeLater {
                saveProfiles()
                status.text = "Подписки обновлены; найдено ссылок: " + imported
                if (errors.isNotEmpty()) JOptionPane.showMessageDialog(this, errors.joinToString("\n"), "Ошибки подписок", JOptionPane.WARNING_MESSAGE)
            }
        }, "chumpy-subscriptions").start()
    }
    private fun pasteLink() {
        val raw = JOptionPane.showInputDialog(this, "Вставь ссылку VMess / VLESS / Trojan / SS / SOCKS / Hy2 / WireGuard:")?.trim().orEmpty()
        if (raw.isBlank()) return
        val profile = LinkParsers.parse(raw)
        if (profile == null) { JOptionPane.showMessageDialog(this, "Не удалось распознать ссылку или в ней не хватает обязательных данных."); return }
        if ((0 until profiles.size()).none { profiles.getElementAt(it) == raw }) profiles.addElement(raw)
        saveProfiles()
        status.text = "Добавлен профиль: " + profile.server
    }
    private fun importQrImage() {
        val chooser = JFileChooser().apply { fileFilter = FileNameExtensionFilter("QR images", "png", "jpg", "jpeg", "bmp") }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        try {
            val decoded = QrImageImporter.read(chooser.selectedFile)
            val links = decoded.lineSequence().map(String::trim).filter { LinkParsers.parse(it) != null }.toList()
            if (links.isEmpty()) error("QR-код не содержит поддерживаемых ссылок Chumpy.")
            links.forEach { link -> if ((0 until profiles.size()).none { profiles.getElementAt(it) == link }) profiles.addElement(link) }
            saveProfiles()
            status.text = "QR импортирован: " + links.size + " ссылок"
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, e.message ?: e.javaClass.simpleName, "QR-импорт", JOptionPane.ERROR_MESSAGE)
        }
    }
    private fun importLinks() {
        val chooser = JFileChooser().apply { isMultiSelectionEnabled = true; fileFilter = FileNameExtensionFilter("Текстовые файлы", "txt", "conf") }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        chooser.selectedFiles.forEach { file ->
            file.readLines().map(String::trim).filter { LinkParsers.parse(it) != null }.forEach { line ->
                if ((0 until profiles.size()).none { profiles.getElementAt(it) == line }) profiles.addElement(line)
            }
        }
        saveProfiles()
        status.text = "Импортировано поддерживаемых ссылок: ${profiles.size()}"
    }
    private fun saveProfiles() { profilesFile.writeText((0 until profiles.size()).joinToString("\n") { profiles.getElementAt(it) }) }
    private fun generateTunConfig() {
        val raw = profileList.selectedValue
        if (raw.isNullOrBlank()) { JOptionPane.showMessageDialog(this, "Сначала выберите импортированную ссылку."); return }
        try {
            val profile = LinkParsers.parse(raw) ?: error("Ссылка больше не распознаётся.")
            val generated = WindowsSingBoxConfigBuilder.write(profile, home)
            configPath.text = generated.absolutePath
            mode.selectedItem = ConnectionMode.TUN
            status.text = "TUN-конфигурация создана для " + profile.type.name
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, e.message ?: e.javaClass.simpleName, "Генерация TUN", JOptionPane.ERROR_MESSAGE)
        }
    }
    private fun generateConfig() {
        val raw = profileList.selectedValue
        if (raw.isNullOrBlank()) { JOptionPane.showMessageDialog(this, "Сначала выберите импортированную ссылку."); return }
        try {
            val profile = LinkParsers.parse(raw) ?: error("Ссылка больше не распознаётся.")
            val generated = WindowsXrayConfigBuilder.write(profile, home)
            configPath.text = generated.absolutePath
            mode.selectedItem = ConnectionMode.SYSTEM_PROXY
            status.text = "Xray-конфигурация создана для " + profile.type.name
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, e.message ?: e.javaClass.simpleName, "Импорт профиля", JOptionPane.ERROR_MESSAGE)
        }
    }
    private fun connect() {
        val selected = mode.selectedItem as ConnectionMode
        val exe = File(enginePath.text.trim()); val config = File(configPath.text.trim())
        if (!exe.isFile || !config.isFile) {
            JOptionPane.showMessageDialog(this, "Укажите существующие файлы движка и JSON-конфигурации.")
            return
        }
        try {
            engine.start(exe, config, selected == ConnectionMode.TUN)
            if (selected == ConnectionMode.SYSTEM_PROXY) proxy.enable("127.0.0.1:10809")
            status.text = "Запущено: ${selected.label}"
        } catch (e: Exception) {
            runCatching { engine.stop() }; runCatching { proxy.restore() }
            JOptionPane.showMessageDialog(this, e.message ?: e.javaClass.simpleName, "Chumpy", JOptionPane.ERROR_MESSAGE)
            status.text = "Ошибка запуска"
        }
    }
    private fun disconnect() {
        runCatching { proxy.restore() }; runCatching { engine.stop() }
        status.text = "Отключено"
    }
}
