def smartbnbInstalled() {
  debugger("Installed with settings: ${settings}")
  smartbnbInitialize()
}

def smartbnbUpdated() {
  debugger("Updated with settings: ${settings}")
  smartbnbInitialize()
}

def smartbnbInitialize() {
  // reset listeners
  unsubscribe()
  unschedule()

  // setup data
  initializeLockData()
  initializeLocks()
  initializeState()

  // set listeners
  state.first = true
  smartbnbCheckCode()
}

def smartbnbUninstalled() {
  unschedule()

  // prompt locks to delete this smartbnb
  initializeLocks()
}

def initializeState() {
  if (!state.userCode) { state.userCode = '' }
  if (!state.startDate) { state.startDate = '' }
  if (!state.endDate) { state.endDate = '' }
}

def smartbnbLandingPage() {
  if (userName) {
    smartbnbMainPage()
  } else {
    smartbnbSetupPage()
  }
}

def smartbnbSetupPage() {
  dynamicPage(name: 'smartbnbSetupPage', title: 'Setup Lock', nextPage: 'smartbnbMainPage', uninstall: true) {
    section('Choose details for this smartbnb') {
      def defaultTime = timeToday("13:00", timeZone()).format(smartThingsDateFormat(), timeZone())
      def defaultEarlyCheckin = timeToday("8:00", timeZone()).format(smartThingsDateFormat(), timeZone())
      def defaultLateCheckout = timeToday("17:00", timeZone()).format(smartThingsDateFormat(), timeZone())
      input(name: 'userSlot',
        type: 'enum',
        options: parent.availableSlots(settings.userSlot),
        title: 'Select slot',
        required: true,
        refreshAfterSelection: true )
      input(name: 'userName',
        title: 'Name for Smartbnb',
        required: true, defaultValue: 'Smartbnb',
        image: 'https://images.lockmanager.io/app/v1/images/user.png')
      input(name: 'url',
        title: 'Request URL',
        required: true)
    }
  }
}

def smartbnbMainPage() {
  //reset errors on each load
  dynamicPage(name: 'smartbnbMainPage', title: '', install: true, uninstall: true) {
    section('Smartbnb Settings') {
      def usage = getAllLocksUsage()
      def text
      if (isActive()) {
        text = 'active'
      } else {
        text = 'inactive'
      }
      paragraph "${text}/${usage}"
      paragraph("User Code: " + state.userCode)
      paragraph("Start: " + state.eventStart)
      paragraph("End: " + state.eventEnd)
      input(name: 'userEnabled',
        type: 'bool',
        title: "Smartbnb Enabled?",
        required: false,
        defaultValue: true,
        refreshAfterSelection: true)
      input(name: 'url',
        title: 'Server URL',
        required: true)
      input(name: 'notification',
        type: 'bool',
        title: 'Send A Push Notification',
        description: 'Notification',
        required: false,
        defaultValue: true)
      input(name: 'muteAfterCheckin',
        title: 'Mute after checkin',
        type: 'bool',
        defaultValue: true,
        required: false,
        image: 'https://images.lockmanager.io/app/v1/images/bell-slash-o.png')
      input(name:
        'notifyCodeChange',
        title: 'when Code changes',
        type: 'bool',
        defaultValue: true,
        required: false,
        image: 'https://images.lockmanager.io/app/v1/images/check-circle-o.png')
      input(name: 'notifyAccess',
        title: 'on Smartbnb Entry',
        type: 'bool',
        required: false,
        image: 'https://images.lockmanager.io/app/v1/images/unlock-alt.png')
    }
    section('Locks') {
      initializeLockData()
      def lockApps = parent.getLockApps()

      lockApps.each { app ->
        href(name: "toLockPage${app.lock.id}",
          page: 'userLockPage',
          params: [id: app.lock.id],
          description: lockPageDescription(app.lock.id),
          required: false,
          title: app.lock.label,
          image: lockPageImage(app.lock) )
      }
    }
    section('Setup', hideable: true, hidden: true) {
      label(title: "Name for App",
        defaultValue: 'Smartbnb: ' + userName,
        required: false,
        image: 'https://images.lockmanager.io/app/v1/images/smartbnb.png')
      input(name: 'userName',
        title: "Name for smartbnb",
        defaultValue: 'Smartbnb',
        required: false,
        image: 'https://images.lockmanager.io/app/v1/images/smartbnb.png')
      input(name: "userSlot",
        type: "enum",
        options: parent.availableSlots(settings.userSlot),
        title: "Select slot",
        required: true,
        refreshAfterSelection: true )
    }
  }
}

def sendSmartbmbMessage(msg) {
  def hubName = location.getName()
  msg = hubName + ": " + msg
  if (notification) {
    checkIfNotifySmartbmb(msg)
  } else {
    checkIfNotifyGlobal(msg)
  }
}

def checkIfNotifySmartbmb(msg) {
  if (muteAfterCheckin != null && muteAfterCheckin) {
    if (getAllLocksUsage() < 2) {
      sendMessageViaUser(msg)
    }
  } else {
    sendMessageViaUser(msg)
  }
}

def getSmartbnbCode() {
  return state.userCode
}

def resetAllLocksUsage() {
  def lockApps = parent.getLockApps()
  lockApps.each { lockApp ->
    if (state."lock${lockApp.lock.id}"?.usage) {
      state."lock${lockApp.lock.id}"?.usage = 0
    }
  }
}

def setLockCodeAfterStateMatches(data) {
  def newCode = data.newCode
  debugger("testing new code: ${newCode}, state.userCode: ${state.userCode}")
  if (!codeIsSet(newCode)) {
    if (state.userCode == newCode) {
      if (settings.notifyCodeChange) {
        sendSmartbmbMessage("Setting code ${settings.userSlot} to ${state.userCode}")
      }
      resetAllLocksUsage()
      parent.setAccess()
    } else {
      // wait another minute and try again
      runIn(60, 'setLockCodeAfterStateMatches', [data:[newCode:newCode]])
    }
  }
}

def scheduleCodeCheck() {
  runEvery15Minutes('smartbnbCheckCode')
}

// Smartbnb Automatic code setting
def smartbnbCheckCode() {
  def now = new Date().format("yyyy_MM_dd_HH_mm", timeZone())
  debugger(now)
  def params = [
    uri: url + '/' + now
  ]
  debugger(params)
  asynchttp_v1.get('smartbnbCallback', params)
  if (state.first == true) {
    state.first = false
    runIn(60, 'scheduleCodeCheck')
  }
}

def codeIsSet(code) {
  def lockApps = parent.getLockApps()
  def ret = true
  lockApps.each { app ->
    def lockApp = parent.getLockAppById(app.lock.id)
    def slotData = lockApp.slotData(userSlot)
    if (!state."lock${app.lock.id}".enabled) {
      debugger("resetting lock: ${app.lock.id}")
      sendSmartbmbMessage("Lock was disabled, resetting to try again")
      lockReset(app.lock.id)
    }
    debugger("lock ${app.lock.id}: slotData: ${slotData}, code: ${code}")
    if (slotData.code != code) {
      ret = false
    }
  }
  debugger("isCodeSet: ${ret}")
  return ret
}

def smartbnbCallback(response, data) {
  if (response.hasError()) {
    debugger("response received error: ${response.getErrorMessage()}")
    return
  }

  debugger("smartbnbCallback: ${response.json}")
  def result
  try {
    result = response.json
  } catch (e) {
    log.error "error parsing json from response: $e"
  }
  if (result) {
    if (!codeIsSet(result['code'])) {
      debugger("Setting user code: ${result}")
      state.userCode = result['code']
      state.eventStart = result['start_date']
      state.eventEnd = result['end_date']

      runIn(60, 'setLockCodeAfterStateMatches', [data: [newCode: result['code']]])
    }
  } else if (state.userCode != '') {
    // no result means the day is completely unbooked
    // uset code and zero out state
    initializeState()
    resetAllLocksUsage()
    parent.setAccess()
    if (settings.notifyCodeChange) {
      sendSmartbmbMessage("Clearing code ${settings.userSlot}")
    }
  }
}
