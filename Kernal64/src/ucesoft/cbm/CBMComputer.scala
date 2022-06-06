package ucesoft.cbm

import ucesoft.cbm.cpu.{CPU65xx, Memory}
import ucesoft.cbm.formats.Diskette
import ucesoft.cbm.misc._
import ucesoft.cbm.peripheral.c2n.Datassette
import ucesoft.cbm.peripheral.drive.{D1581, Drive, DriveType, EmptyFloppy}
import ucesoft.cbm.peripheral.keyboard
import ucesoft.cbm.peripheral.keyboard.Keyboard
import ucesoft.cbm.peripheral.printer.{MPS803GFXDriver, MPS803ROM, Printer}
import ucesoft.cbm.peripheral.vic.Display
import ucesoft.cbm.trace.{InspectPanelDialog, TraceDialog, TraceListener}

import java.awt.{BorderLayout, Color, FlowLayout}
import java.awt.event.{MouseAdapter, MouseEvent, WindowAdapter, WindowEvent}
import java.io.{BufferedReader, File, FileInputStream, FileReader, FileWriter, IOException, InputStreamReader}
import java.util.Properties
import javax.swing._

object CBMComputer {
  def turnOn(computer : => CBMHomeComputer, args:Array[String]) : Unit = {
    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())

    val cbm = computer
    try {
      cbm.turnOn(args)
    }
    catch {
      case i:Preferences.PreferenceIllegalArgumentException =>
        println(s"Bad command line argument: ${i.getMessage}")
        sys.exit(100)
      case t:Throwable =>
        cbm.errorHandler(t)
        if (cbm.isHeadless) sys.exit(1)
    }
  }
}

abstract class CBMComputer extends CBMComponent {
  protected val cbmModel : CBMComputerModel

  protected val TOTAL_DRIVES = Preferences.TOTALDRIVES
  protected val APPLICATION_NAME : String
  protected val CONFIGURATION_FILENAME : String
  protected val CONFIGURATION_LASTDISKDIR = "lastDiskDirectory"
  protected val CONFIGURATION_FRAME_XY = "frame.xy"
  protected val CONFIGURATION_FRAME_DIM = "frame.dim"
  protected val CONFIGURATION_KEYB_MAP_FILE = "keyb.map.file"

  protected def PRG_RUN_DELAY_CYCLES = 2200000
  protected var lastLoadedPrg : Option[File] = None
  protected var headless = false // used with --testcart command options
  protected var cpujamContinue = false // used with --cpujam-continue
  protected var zoomOverride = false // used with --screen-dim
  protected var sidCycleExact = false // used with --sid-cycle-exact
  protected var loadStateFromOptions = false // used with --load-state
  protected var traceOption = false // used with --trace
  protected var fullScreenAtBoot = false // used with --fullscreen
  protected var ignoreConfig = false // used with --ignore-config-file

  protected val cartMenu = new JMenu("Cartridge")
  protected var cartButtonRequested = false

  protected val keyb : Keyboard

  protected var display : Display = _
  protected var gifRecorder : JDialog = _

  protected lazy val displayFrame = {
    val f = new JFrame(s"$APPLICATION_NAME " + ucesoft.cbm.Version.VERSION)
    f.addWindowListener(new WindowAdapter {
      override def windowClosing(e:WindowEvent) : Unit = turnOff
    })
    f.setIconImage(new ImageIcon(getClass.getResource("/resources/commodore.png")).getImage)
    f
  }

  protected def isC64Mode : Boolean = false

  // memory & main cpu
  protected val mmu : Memory
  protected lazy val cpu = CPU65xx.make(mmu)
  // main chips
  protected val clock = Clock.setSystemClock(Some(errorHandler _))(mainLoop _)
  // -------------------- TAPE -----------------
  protected val tapeAllowed = true
  protected var datassette : Datassette = _
  // -------------------- DISK -----------------
  protected val drives : Array[Drive with TraceListener] = Array.ofDim(TOTAL_DRIVES)
  protected var device12Drive : Drive = _
  protected var device12DriveEnabled = false
  protected var canWriteOnDisk = true
  protected val drivesRunning = Array.fill[Boolean](TOTAL_DRIVES)(true)
  protected val drivesEnabled = Array.fill[Boolean](TOTAL_DRIVES)(true)
  protected lazy val diskFlusher = new FloppyFlushUI(displayFrame)
  protected val driveLeds = (for(d <- 0 until TOTAL_DRIVES) yield {
    val led = new DriveLed(d + 8)
    led.addMouseListener(new MouseAdapter {
      override def mouseClicked(e: MouseEvent): Unit = attachDisk(d,false,isC64Mode)
    })
    led
  }).toArray
  protected val floppyComponents = Array.ofDim[FloppyComponent](TOTAL_DRIVES)
  protected val driveLedListeners = {
    (for(d <- 0 until TOTAL_DRIVES) yield {
      new AbstractDriveLedListener(driveLeds(d)) {
        if (d > 0) driveLeds(d).setVisible(false)
      }
    }).toArray
  }
  // -------------------- PRINTER --------------
  protected var printerEnabled = false
  protected val printerGraphicsDriver = new MPS803GFXDriver(new MPS803ROM)
  protected val printer : Printer
  protected lazy val printerDialog = {
    val dialog = new JDialog(displayFrame,"Print preview")
    val sp = new JScrollPane(printerGraphicsDriver)
    sp.getViewport.setBackground(Color.BLACK)
    dialog.getContentPane.add("Center",sp)
    printerGraphicsDriver.checkSize
    val buttonPanel = new JPanel
    val exportPNGBUtton = new JButton("Export as PNG")
    buttonPanel.add(exportPNGBUtton)
    exportPNGBUtton.addActionListener(_ => printerSaveImage )
    val clearButton = new JButton("Clear")
    buttonPanel.add(clearButton)
    clearButton.addActionListener(_ => printerGraphicsDriver.clearPages )
    dialog.getContentPane.add("South",buttonPanel)
    dialog.pack
    dialog
  }
  // -------------- MENU ITEMS -----------------
  protected val maxSpeedItem = new JCheckBoxMenuItem("Warp mode")
  protected val loadFileItems = for(d <- 0 until TOTAL_DRIVES) yield new JMenuItem(s"Load file from attached disk ${d + 8} ...")
  protected val tapeMenu = new JMenu("Tape control...")

  // -------------------- TRACE ----------------
  protected var traceDialog : TraceDialog = _
  protected var diskTraceDialog : TraceDialog = _
  protected var inspectDialog : InspectPanelDialog = _
  protected var traceItem,traceDiskItem : JCheckBoxMenuItem = _

  // ------------------------------------ Drag and Drop ----------------------------
  protected val DNDHandler = new DNDHandler(handleDND(_,true,true))

  protected val preferences = new Preferences

  protected lazy val configuration = {
    val kernalConfigHome = System.getProperty("kernal.config",scala.util.Properties.userHome)
    val props = new Properties
    val propsFile = new File(new File(kernalConfigHome),CONFIGURATION_FILENAME)
    if (propsFile.exists) {
      try {
        props.load(new FileReader(propsFile))
      }
      catch {
        case _:IOException =>
          setDefaultProperties(props)
      }
    }
    else {
      setDefaultProperties(props)
    }

    configurationLoaded(props)
    props
  }

  def turnOn(args:Array[String]) : Unit

  def turnOff : Unit = {
    if (!headless) saveSettings(preferences[Boolean](Preferences.PREF_PREFAUTOSAVE).getOrElse(false))
    for (d <- drives)
      d.getFloppy.close
    shutdownComponent
    sys.exit(0)
  }

  protected def reset(play:Boolean=true,loadAndRunLastPrg:Boolean = false) : Unit = {
    if (traceDialog != null) traceDialog.forceTracing(false)
    if (diskTraceDialog != null) diskTraceDialog.forceTracing(false)
    if (Thread.currentThread != Clock.systemClock) clock.pause
    resetComponent
    if (loadAndRunLastPrg) lastLoadedPrg.foreach( f =>
      clock.schedule(new ClockEvent("RESET_PRG",clock.currentCycles + PRG_RUN_DELAY_CYCLES,(cycles) => loadPRGFile(f,true)))
    )

    if (play) clock.play
  }

  protected def hardReset(play:Boolean=true) : Unit = {
    if (traceDialog != null) traceDialog.forceTracing(false)
    if (diskTraceDialog != null) diskTraceDialog.forceTracing(false)
    if (Thread.currentThread != Clock.systemClock) clock.pause
    hardResetComponent

    if (play) clock.play
  }

  protected def errorHandler(t:Throwable) : Unit = {
    import CPU65xx.CPUJammedException
    t match {
      case j:CPUJammedException if !cpujamContinue =>
        JOptionPane.showConfirmDialog(displayFrame,
          s"CPU[${j.cpuID}] jammed at " + Integer.toHexString(j.pcError) + ". Do you want to open debugger (yes), reset (no) or continue (cancel) ?",
          "CPU jammed",
          JOptionPane.YES_NO_CANCEL_OPTION,
          JOptionPane.ERROR_MESSAGE) match {
          case JOptionPane.YES_OPTION =>
            if (traceDialog != null) traceDialog.forceTracing(true)
            trace(true,true)
          case JOptionPane.CANCEL_OPTION => // continue
          case _ =>
            reset(true)
        }
      case _:CPUJammedException => // continue
      case _ =>
        Log.info("Fatal error occurred: " + cpu + "-" + t)
        try Log.info(CPU65xx.disassemble(mmu,cpu.getCurrentInstructionPC).toString) catch { case _:Throwable => }
        t.printStackTrace(Log.getOut)
        t.printStackTrace
        if (headless) {
          println(s"Fatal error occurred on cycle ${clock.currentCycles}: $cpu\n${CPU65xx.disassemble(mmu,cpu.getCurrentInstructionPC)}")
          t.printStackTrace
          sys.exit(1)
        } // exit if headless
        JOptionPane.showMessageDialog(displayFrame,t.toString + " [PC=" + Integer.toHexString(cpu.getCurrentInstructionPC) + "]", "Fatal error",JOptionPane.ERROR_MESSAGE)
        //trace(true,true)
        reset(true)
    }
  }

  protected def trace(cpu:Boolean,on:Boolean) : Unit = {
    if (traceDialog == null) return

    if (cpu) {
      Log.setOutput(traceDialog.logPanel.writer)
      traceDialog.setVisible(on)
      traceItem.setSelected(on)
    }
    else {
      if (on) Log.setOutput(diskTraceDialog.logPanel.writer)
      else Log.setOutput(traceDialog.logPanel.writer)
      diskTraceDialog.setVisible(on)
      traceDiskItem.setSelected(on)
    }
  }

  protected def setDisplayRendering(hints:java.lang.Object) : Unit = {
    display.setRenderingHints(hints)
  }

  protected def warpMode(warpOn:Boolean,play:Boolean = true): Unit = {
    maxSpeedItem.setSelected(warpOn)
    clock.maximumSpeed = warpOn
  }

  protected def configurationLoaded(properties: Properties): Unit = {}

  protected def setDefaultProperties(configuration:Properties) : Unit = {
    import Preferences._
    configuration.setProperty(PREF_RENDERINGTYPE,"default")
    configuration.setProperty(PREF_WRITEONDISK,"true")
  }

  protected def saveConfigurationFile : Unit = {
    try {
      val kernalConfigHome = System.getProperty("kernal.config",scala.util.Properties.userHome)
      val propsFile = new File(new File(kernalConfigHome), CONFIGURATION_FILENAME)
      val out = new FileWriter(propsFile)
      configuration.store(out, "Kernal64 configuration file")
      out.close
    }
    catch {
      case _: IOException =>
    }
  }

  protected def showKeyboardEditor(c64Mode:Boolean): Unit = {
    val source = configuration.getProperty(CONFIGURATION_KEYB_MAP_FILE,java.awt.im.InputContext.getInstance().getLocale.getLanguage.toUpperCase())
    val kbef = new JFrame(s"Keyboard editor ($source)")
    val kbe = new KeyboardEditor(keyb,keyb.getKeyboardMapper,cbmModel)
    kbef.getContentPane.add("Center",kbe)
    kbef.pack
    kbef.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE)
    kbef.setVisible(true)
  }

  protected def showAbout  : Unit = {
    val about = new AboutCanvas(getCharROM,ucesoft.cbm.Version.VERSION.toUpperCase + " (" + ucesoft.cbm.Version.BUILD_DATE.toUpperCase + ")")
    JOptionPane.showMessageDialog(displayFrame,about,"About",JOptionPane.INFORMATION_MESSAGE,new ImageIcon(getClass.getResource("/resources/commodore_file.png")))
  }

  protected def showSettings : Unit = {
    val settingsPanel = new SettingsPanel(preferences)
    JOptionPane.showMessageDialog(displayFrame,settingsPanel,"Settings",JOptionPane.INFORMATION_MESSAGE,new ImageIcon(getClass.getResource("/resources/commodore_file.png")))
  }

  protected def ejectDisk(driveID:Int) : Unit = {
    drives(driveID).getFloppy.close
    driveLeds(driveID).setToolTipText("")
    if (traceDialog != null && !traceDialog.isTracing) clock.pause
    if (drives(driveID).driveType == DriveType._1581) drives(driveID).setDriveReader(D1581.MFMEmptyFloppy,true)
    else drives(driveID).setDriveReader(EmptyFloppy,true)
    loadFileItems(driveID).setEnabled(false)
    preferences.updateWithoutNotify(Preferences.PREF_DRIVE_X_FILE(driveID),"")
    clock.play
  }

  protected def enablePrinter(enable:Boolean) : Unit = {
    printerEnabled = enable
    printer.setActive(enable)
  }

  protected def showPrinterPreview : Unit = {
    printerGraphicsDriver.checkSize
    printerDialog.setVisible(true)
  }

  protected def printerSaveImage  : Unit = {
    val fc = new JFileChooser
    fc.showSaveDialog(printerDialog) match {
      case JFileChooser.APPROVE_OPTION =>
        val file = if (fc.getSelectedFile.getName.toUpperCase.endsWith(".PNG")) fc.getSelectedFile else new File(fc.getSelectedFile.toString + ".png")
        printerGraphicsDriver.saveAsPNG(file)
      case _ =>
    }
  }

  protected def makeInfoPanel(includeTape:Boolean) : JPanel = {
    val infoPanel = new JPanel(new BorderLayout)
    val rowPanel = new JPanel()
    rowPanel.setLayout(new BoxLayout(rowPanel,BoxLayout.Y_AXIS))
    for(d <- 0 until TOTAL_DRIVES) {
      val row1Panel = new JPanel(new FlowLayout(FlowLayout.RIGHT,5,2))
      rowPanel.add(row1Panel)
      if (d == 0 && includeTape) {
        val tapePanel = new TapeState(datassette)
        datassette.setTapeListener(tapePanel)
        row1Panel.add(tapePanel)
        row1Panel.add(tapePanel.progressBar)
      }
      row1Panel.add(driveLeds(d))
    }
    infoPanel.add("East",rowPanel)
    infoPanel
  }

  protected def initializedDrives(defaultDriveType:DriveType.Value) : Unit = {
    for(d <- 0 until TOTAL_DRIVES) {
      initDrive(d,defaultDriveType)
      if (d > 0) drivesEnabled(d) = false
      floppyComponents(d) = new FloppyComponent(8 + d,drives(d),driveLeds(d))
      add(floppyComponents(d))
      add(driveLeds(d))
    }
  }

  protected def writeOnDiskSetting(enabled:Boolean) : Unit = {
    canWriteOnDisk = enabled
    for(d <- 0 until TOTAL_DRIVES) drives(d).getFloppy.canWriteOnDisk = canWriteOnDisk
  }

  protected def loadKeyboard  : Unit = {
    JOptionPane.showConfirmDialog(displayFrame,"Would you like to set default keyboard or load a configuration from file ?","Keyboard layout selection", JOptionPane.YES_NO_CANCEL_OPTION,JOptionPane.QUESTION_MESSAGE) match {
      case JOptionPane.YES_OPTION =>
        configuration.remove(CONFIGURATION_KEYB_MAP_FILE)
        JOptionPane.showMessageDialog(displayFrame,"Reboot the emulator to activate the new keyboard", "Keyboard..",JOptionPane.INFORMATION_MESSAGE)
      case JOptionPane.NO_OPTION =>
        val fc = new JFileChooser
        fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
        fc.setDialogTitle("Choose a keyboard layout")
        fc.showOpenDialog(displayFrame) match {
          case JFileChooser.APPROVE_OPTION =>
            val in = new BufferedReader(new InputStreamReader(new FileInputStream(fc.getSelectedFile)))
            try {
              keyboard.KeyboardMapperStore.load(in)
              configuration.setProperty(CONFIGURATION_KEYB_MAP_FILE,fc.getSelectedFile.toString)
              JOptionPane.showMessageDialog(displayFrame,"Reboot the emulator to activate the new keyboard", "Keyboard..",JOptionPane.INFORMATION_MESSAGE)
            }
            catch {
              case _:IllegalArgumentException =>

                showError("Keyboard..","Invalid keyboard layout file")
            }
            finally {
              in.close
            }
          case _ =>
        }
      case JOptionPane.CANCEL_OPTION =>
    }
  }

  protected def setDriveType(drive:Int,dt:DriveType.Value,dontPlay:Boolean = false) : Unit = {
    clock.pause
    initDrive(drive,dt)
    preferences.updateWithoutNotify(Preferences.PREF_DRIVE_X_TYPE(drive),dt.toString)
    if (!dontPlay) clock.play
  }

  protected def makeDisk : Unit = {
    val fc = new JFileChooser
    fc.setCurrentDirectory(new File(configuration.getProperty(CONFIGURATION_LASTDISKDIR,"./")))
    fc.setFileView(new C64FileView)
    fc.setDialogTitle("Make an empty disk")
    fc.showSaveDialog(displayFrame) match {
      case JFileChooser.APPROVE_OPTION =>
        try {
          Diskette.makeEmptyDisk(fc.getSelectedFile.toString)
        }
        catch {
          case t:Throwable =>
            showError("Disk making error",t.toString)
        }
      case _ =>
    }
  }

  protected def openGIFRecorder : Unit = gifRecorder.setVisible(true)

  protected def setMenu(enableCarts:Boolean,enableGames:Boolean) : Unit = {
    val menuBar = new JMenuBar
    val fileMenu = new JMenu("File")
    val editMenu = new JMenu("Edit")
    val stateMenu = new JMenu("State")
    val traceMenu = new JMenu("Trace")
    val optionMenu = new JMenu("Settings")
    val gamesMenu = new JMenu("Games")
    val helpMenu = new JMenu("Help")

    if (enableCarts) cartMenu.setVisible(false)

    menuBar.add(fileMenu)
    menuBar.add(editMenu)
    menuBar.add(stateMenu)
    menuBar.add(traceMenu)
    menuBar.add(optionMenu)
    if (enableCarts) menuBar.add(cartMenu)
    if (enableGames) menuBar.add(gamesMenu)
    menuBar.add(helpMenu)

    setFileMenu(fileMenu)
    setEditMenu(editMenu)
    setStateMenu(stateMenu)
    setTraceMenu(traceMenu)
    setSettingsMenu(optionMenu)
    if (enableGames) setGameMenu(gamesMenu)
    setHelpMenu(helpMenu)

    if (enableCarts) {
      val cartInfoItem = new JMenuItem("Cart info ...")
      cartInfoItem.addActionListener(_ => showCartInfo)
      cartMenu.add(cartInfoItem)
      val cartButtonItem = new JMenuItem("Press cartridge button...")
      cartButtonItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z, java.awt.event.InputEvent.ALT_DOWN_MASK))
      cartButtonItem.addActionListener(_ => cartButtonRequested = true)
      cartMenu.add(cartButtonItem)
    }

    displayFrame.setJMenuBar(menuBar)

    setGlobalCommandLineOptions

  }


  protected def swing(f: => Unit) : Unit = SwingUtilities.invokeAndWait(() => f)

  // Abstract methods
  protected def setFileMenu(menu: JMenu): Unit
  protected def setEditMenu(menu: JMenu): Unit
  protected def setStateMenu(menu: JMenu): Unit
  protected def setTraceMenu(menu: JMenu): Unit
  protected def setGameMenu(menu: JMenu): Unit = {}
  protected def setHelpMenu(menu: JMenu): Unit
  protected def showCartInfo : Unit = {}

  protected def setGlobalCommandLineOptions : Unit = {}

  protected def getCharROM : Memory

  protected def initComputer : Unit

  protected def handleDND(file:File,_reset:Boolean,autorun:Boolean) : Unit

  protected def loadPRGFile(file:File,autorun:Boolean) : Unit

  protected def saveSettings(save:Boolean) : Unit

  protected def setSettingsMenu(optionsMenu:JMenu) : Unit

  protected def paste : Unit

  protected def mainLoop(cycles:Long) : Unit

  protected def initDrive(id:Int,driveType:DriveType.Value) : Unit

  protected def attachDisk(driveID:Int,autorun:Boolean,c64Mode:Boolean) : Unit
}
