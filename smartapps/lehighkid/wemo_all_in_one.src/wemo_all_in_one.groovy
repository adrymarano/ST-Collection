/**
 *  Copyright 2015 SmartThings
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Wemo Service Manager
 *
 *  Author: superuser-ule (fix)
 *  Date: 2016-02-06
 *  Last Update : 2016-02-24
 */
definition(
    name: "Wemo All In One (Connect) Advanced",
    namespace: "lehighkid",
    author: "SmartThings,Ule,mujica",
    description: "Allows you to integrate your WeMo Switch and Wemo Motion sensor with SmartThings.",
    category: "SmartThings Labs",
    singleInstance: true,
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Partner/wemo.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Partner/wemo@2x.png"
)

preferences {
    page(name: "MainPage", title: "Search and config your Wemo Devices", install:true, uninstall: true){
    	section("") {
            href(name: "discover",title: "Discovery process",required: false,page: "wemoDiscovery",description: "tap to start searching")
            href(name: "watchdog",title: "Watchdog Settings",required: false,page: "wemoWatchdog", description: "tap to config the Watchdog Timer")
            input "searchTarget", "string", title: "Search Target:\nUse '/' to separate devicetypes", defaultValue: "urn:Belkin:device:insight:1/urn:Belkin:device:controllee:1/urn:Belkin:device:sensor:1/urn:Belkin:device:lightswitch:1/urn:Belkin:device:dimmer:1/urn:Belkin:device:maker:1", required: true
            // urn:Belkin:device:insight:1/urn:Belkin:device:controllee:1/urn:Belkin:device:sensor:1/urn:Belkin:device:lightswitch:1/urn:Belkin:device:dimmer:1/urn:Belkin:device:maker:1
			input "interval", "number", title:"Set refresh minutes", defaultValue:5
            input "configLoggingLevelIDE", "enum", title: "IDE Live Logging Level:\nMessages with this level and higher will be logged to the IDE", options: ["0" : "None", "1" : "Error", "2" : "Warning", "3" : "Info", "4" : "Debug", "5" : "Trace"], defaultValue: "3", required: true
            input "skipFirmware", "bool", title: "Skip firmware check", required: false, defaultValue: false
        	input "debug", "bool", title: "Enable debugging for application", required: false, defaultValue: false
        }
    }
    
    page(name: "wemoWatchdog", title:"Config the Watchdog Timer")
    page(name: "wemoDiscovery", title:"Discovery Started!")
    
}

def wemoDiscovery(){
    state.loggingLevelIDE = 5
    logger("Device Discovery Started", "trace")
    
	if(canInstallLabs() || skipFirmware )
	{
		int refreshCount = !state.refreshCount ? 0 : state.refreshCount as int
		state.refreshCount = refreshCount + 1
		def refreshInterval = 5000

		if(!state.subscribe) {
			subscribe(location, null, locationHandlerWemo, [filterEvents:false])
			state.subscribe = true
		}

		//ssdp request every 25 seconds
		if((refreshCount % 5) == 0) {
			discoverAllWemoTypes()
		}

		//setup.xml request every 5 seconds except on discoveries
		if(((refreshCount % 1) == 0) && ((refreshCount % 5) != 0)) {
			verifyDevices()
		}

		def switchesDiscovered = switchesDiscovered()

		return dynamicPage(name:"wemoDiscovery", title:"Discovery Started!", refreshInterval: refreshInterval) {
			section("Select a device...") {
				input "selectedSwitches", "enum", required:false, title:"Select Wemo Devices \n(${switchesDiscovered.size() ?: 0} found)", multiple:true, options:switchesDiscovered
			}
		}
	}
	else
	{
		def upgradeNeeded = """To use SmartThings Labs, your Hub should be completely up to date. To update your Hub, access Location Settings in the Main Menu (tap the gear next to your location name), select your Hub, and choose "Update Hub"."""

		return dynamicPage(name:"wemoDiscovery", title:"Upgrade needed!", nextPage:"", install:false, uninstall: true) {
			section("Upgrade") {
				paragraph "$upgradeNeeded"
			}
		}
	}
}

private discoverAllWemoTypes() {
    logger("discoverAllWemoTypes Started", "trace")
    
	if(!state.subscribe) {
        subscribe(location, null, locationHandlerWemo, [filterEvents:false])
        state.subscribe = true
    }
    
    logger("discoverAllWemoTypes Ended", "trace")
    
    sendHubCommand(new physicalgraph.device.HubAction("lan discovery ${searchTarget}", physicalgraph.device.Protocol.LAN))
}

def switchesDiscovered() {
    logger("switchesDiscovered Started", "trace")
    
	def switches = getWemoSwitches().findAll{ it?.value?.verified == true }
	def map = [:]
	switches.each {	
		def value = it.value.name ?: "WeMo Switch ${it.value.ssdpUSN.split(':')[1][-3..-1]}"
		def key = it.value.mac
		map["${key}"] = value
	}
    
	map
}

def getWemoSwitches() {
    logger("getWemoSwitches Started", "trace")
        
    //logger("state.switches: ${state.switches}", "debug")
    
	if (!state.switches) { state.switches = [:] }
 	state.switches
}



private verifyDevices(){
    logger("verifyDevices Started", "trace")
    
	def devices = getWemoSwitches().findAll{ it?.value?.verified != true }

    logger("Unverified devices: ${devices}", "debug")
    
	devices.each {
		int port = convertHexToInt(it.value.port)
		String ip = convertHexToIP(it.value.ip)
		String host = "${ip}:${port}"
		sendHubCommand(new physicalgraph.device.HubAction("""GET /setup.xml HTTP/1.1\r\nHOST: $host\r\n\r\n""", physicalgraph.device.Protocol.LAN, host, [callback: deviceDescriptionHandler]))
	}
}

void deviceDescriptionHandler(physicalgraph.device.HubResponse hubResponse) {
    logger("deviceDescriptionHandler Started", "trace")
    
	String contentType = hubResponse?.headers['Content-Type']
    
    logger("Response received from ${convertHexToIP(hubResponse.ip)}:${convertHexToInt(hubResponse.port)}", "debug")
    
	if (contentType != null && contentType == 'text/xml') {
		def body = hubResponse.xml

        def devices = getWemoSwitches()
        
		def wemoDevice = devices.find {it?.key?.contains(body?.device?.UDN?.text())}
        
		if (wemoDevice) {
        	def friendlyName = body?.device?.friendlyName?.text()
            
            logger("Verified '${friendlyName}'", "debug")
            
			wemoDevice.value << [name: friendlyName, verified: true]
		} else {
            
			logger("Device setup path does not exist and cannot be verified", "error");
            
		}
	}
}

def addSwitches() {
    logger("addSwitches Started", "trace")
    
	def switches = getWemoSwitches()

	selectedSwitches.each { dni ->
		def selectedSwitch = switches.find { it.value.mac == dni } ?: switches.find { "${it.value.ip}:${it.value.port}" == dni }
		def d
		if (selectedSwitch) {
			d = getChildDevices()?.find {
				it.dni == selectedSwitch.value.mac || it.device.getDataValue("mac") == selectedSwitch.value.mac
			}
		}

        //log.trace "${selectedSwitch}"
        
		if (!d) {
			
            logger("Creating WeMo with dni: ${selectedSwitch.value.mac}", "debug")
            
			def name
			def namespace
			switch (selectedSwitch.value.ssdpTerm){
				case ~/.*lightswitch.*/: 
					name = "Wemo Light Switch"
                	namespace = "lehighkid"
					break
				case ~/.*sensor.*/: 
					name = "Wemo Motion"
                	namespace = "mujica"
					break
				case ~/.*controllee.*/: 
					name = "Wemo Switch"
                	namespace = "mujica"
					break
				case ~/.*dimmer.*/: 
					name = "Wemo Dimmer Light Switch"
                	namespace = "lehighkid"
					break
				case ~/.*insight.*/: 
					name = "Wemo Insight Switch"
                	namespace = "lehighkid"
					break
				case ~/.*maker.*/: 
					name = "Wemo Maker"
                	namespace = "lehighkid"
					break
			}
 			d = addChildDevice(namespace, name , selectedSwitch.value.mac, selectedSwitch?.value.hub, [ 
            		"label":  selectedSwitch?.value?.name ?:"Wemo Device",
					"data": [
							"mac": selectedSwitch.value.mac,
							"ip": selectedSwitch.value.ip,
							"port": selectedSwitch.value.port
					]
			])
            
			logger("Created ${d.displayName} with id: ${d.id}, dni: ${d.deviceNetworkId}", "debug")
		
        } else {
		
            logger("Found ${d.displayName} with id $dni already exists", "debug")
		
        }
	}
}

private def parseDiscoveryMessage(String description) {
	def device = [:]
	def parts = description.split(',')
	parts.each { part ->
		part = part.trim()
		if (part.startsWith('devicetype:')) {
			def valueString = part.split(":")[1].trim()
			device.devicetype = valueString
		}
		else if (part.startsWith('mac:')) {
			def valueString = part.split(":")[1].trim()
			if (valueString) {
				device.mac = valueString
			}
		}
		else if (part.startsWith('networkAddress:')) {
			def valueString = part.split(":")[1].trim()
			if (valueString) {
				device.ip = valueString
			}
		}
		else if (part.startsWith('deviceAddress:')) {
			def valueString = part.split(":")[1].trim()
			if (valueString) {
				device.port = valueString
			}
		}
		else if (part.startsWith('ssdpPath:')) {
			def valueString = part.split(":")[1].trim()
			if (valueString) {
				device.ssdpPath = valueString
			}
		}
		else if (part.startsWith('ssdpUSN:')) {
			part -= "ssdpUSN:"
			def valueString = part.trim()
			if (valueString) {
				device.ssdpUSN = valueString
			}
		}
		else if (part.startsWith('ssdpTerm:')) {
			part -= "ssdpTerm:"
			def valueString = part.trim()
			if (valueString) {
				device.ssdpTerm = valueString
			}
		}
		else if (part.startsWith('headers')) {
			part -= "headers:"
			def valueString = part.trim()
			if (valueString) {
				device.headers = valueString
			}
		}
		else if (part.startsWith('body')) {
			part -= "body:"
			def valueString = part.trim()
			if (valueString) {
				device.body = valueString
			}
		}
	}

	device
}

def locationHandlerWemo(evt) {
	def description = evt.description
	def hub = evt?.hubId
	def parsedEvent = parseDiscoveryMessage(description)
	parsedEvent << ["hub":hub]
    def msg = parseLanMessage(description)
	log.trace "parsedEvent: ${parsedEvent}"
    //parsedEvent: [mac:6038E04223A9, ip:0A00018E, port:c001, ssdpPath:/setup.xml, ssdpUSN:uuid:Lightswitch-1_0-221639K1300186::urn:Belkin:device:lightswitch:1, ssdpTerm:urn:Belkin:device:lightswitch:1, hub:1]
    log.trace "msg: ${msg}"
    //msg: [mac:149182D747C9, networkAddress:0A0001F1, deviceAddress:c002, ssdpPath:/setup.xml, ssdpUSN:uuid:Dimmer-1_0-241714K1500249::urn:Belkin:device:dimmer:1, ssdpTerm:urn:Belkin:device:dimmer:1, ssdpNTS:null]
    
	if (parsedEvent?.ssdpTerm?.contains("Belkin:device:maker") || parsedEvent?.ssdpTerm?.contains("Belkin:device:lightswitch") || parsedEvent?.ssdpTerm?.contains("Belkin:device:sensor") || parsedEvent?.ssdpTerm?.contains("Belkin:device:controllee") || parsedEvent?.ssdpTerm?.contains("Belkin:device:insight") || parsedEvent?.ssdpTerm?.contains("Belkin:device:dimmer")) {
		def switches = getWemoSwitches()

		if (!(switches."${parsedEvent.ssdpUSN.toString()}"))
		{ //if it doesn't already exist
			switches << ["${parsedEvent.ssdpUSN.toString()}":parsedEvent]
		}
		else
		{ // just update the values

			def d = switches."${parsedEvent.ssdpUSN.toString()}"
			boolean deviceChangedValues = false

			if(d.ip != parsedEvent.ip || d.port != parsedEvent.port) {
				d.ip = parsedEvent.ip
				d.port = parsedEvent.port
				deviceChangedValues = true
			}

			if (deviceChangedValues) {
				def children = getChildDevices()
				children.each {
					if (it.getDeviceDataByName("mac") == parsedEvent.mac) {
						log.debug "updating ip and port, and resubscribing, for device ${it} with mac ${parsedEvent.mac}"
						it.subscribe(parsedEvent.ip, parsedEvent.port)
					}
				}
			}

		}

	}
	else if (parsedEvent.headers && parsedEvent.body) {
		String headerString = new String(parsedEvent.headers.decodeBase64())?.toLowerCase()
		if (headerString != null && (headerString.contains('text/xml') || headerString.contains('application/xml'))) {
			def body = parseXmlBody(parsedEvent.body)
			if (body?.device?.deviceType?.text().startsWith("urn:Belkin:device:maker:1") || body?.device?.deviceType?.text().startsWith("urn:Belkin:device:controllee:1") || body?.device?.deviceType?.text().startsWith("urn:Belkin:device:insight:1") || body?.device?.deviceType?.text().startsWith("urn:Belkin:device:sensor") || body?.device?.deviceType?.text().startsWith("urn:Belkin:device:lightswitch:1") || body?.device?.deviceType?.text().startsWith("urn:Belkin:device:dimmer:1"))
			{
				def switches = getWemoSwitches()
				def wemoSwitch = switches.find {it?.key?.contains(body?.device?.UDN?.text())}
                log.trace "wemoSwitch: ${wemoSwitch}"
                if (wemoSwitch)
				{
					wemoSwitch.value << [name:body?.device?.friendlyName?.text(), verified: true]
				}
				else
				{
					log.error "/setup.xml returned a wemo device that didn't exist"
				}
			}
		}
	}
}

private getHostAddress(d) {
	def parts = d.split(":")
	def ip = convertHexToIP(parts[0])
	def port = convertHexToInt(parts[1])
	return ip + ":" + port
}

def installed() {
    logger("Installed Started", "trace")
    
    state.installedAt = now()
    state.loggingLevelIDE = 5
    
	logger("Installed with settings: ${settings}", "debug")
    
	//initialize()
}

def updated() {
    logger("Updated Started", "trace")
    
    state.loggingLevelIDE = (settings.configLoggingLevelIDE) ? settings.configLoggingLevelIDE.toInteger() : 3
   
    unsubscribe()
	state.subscribe = false
	if (selectedSwitches)
	{
		addSwitches()
	}
    subscribeToEvents()
    scheduleActions()
	scheduledActionsHandler()
}

def initialize() {
    logger("Initialize Started", "trace")
    
	// remove location subscription afterwards
	 unsubscribe()
	 state.subscribe = false

	if (selectedSwitches)
	{
		addSwitches()
	}
}

def wemoWatchdog(){
	dynamicPage(name: "wemoWatchdog") {
    	def anythingSet = anythingSet()
		if (anythingSet) {
			section("Verify Timer When"){
				ifSet "motion", "capability.motionSensor", title: "Motion Here", required: false, multiple: true
				ifSet "contact", "capability.contactSensor", title: "Contact Opens", required: false, multiple: true
				ifSet "contactClosed", "capability.contactSensor", title: "Contact Closes", required: false, multiple: true
				ifSet "acceleration", "capability.accelerationSensor", title: "Acceleration Detected", required: false, multiple: true
				ifSet "mySwitch", "capability.switch", title: "Switch Turned On", required: false, multiple: true
				ifSet "mySwitchOff", "capability.switch", title: "Switch Turned Off", required: false, multiple: true
				ifSet "arrivalPresence", "capability.presenceSensor", title: "Arrival Of", required: false, multiple: true
				ifSet "departurePresence", "capability.presenceSensor", title: "Departure Of", required: false, multiple: true
				ifSet "smoke", "capability.smokeDetector", title: "Smoke Detected", required: false, multiple: true
				ifSet "water", "capability.waterSensor", title: "Water Sensor Wet", required: false, multiple: true
                ifSet "temperature", "capability.temperatureMeasurement", title: "Temperature", required: false, multiple: true
                ifSet "powerMeter", "capability.powerMeter", title: "Power Meter", required: false, multiple: true
                ifSet "energyMeter", "capability.energyMeter", title: "Energy", required: false, multiple: true
                ifSet "signalStrength", "capability.signalStrength", title: "Signal Strength", required: false, multiple: true
				ifSet "button1", "capability.button", title: "Button Press", required:false, multiple:true //remove from production
				ifSet "triggerModes", "mode", title: "System Changes Mode", required: false, multiple: true
			}
		}
		def hideable = anythingSet || (app.installationState == "COMPLETE" && anythingSet)
		def sectionTitle = anythingSet ? "Select additional triggers" : "Verify Timer When..."

		section(sectionTitle, hideable: hideable, hidden: true){
			ifUnset "motion", "capability.motionSensor", title: "Motion Here", required: false, multiple: true
			ifUnset "contact", "capability.contactSensor", title: "Contact Opens", required: false, multiple: true
			ifUnset "contactClosed", "capability.contactSensor", title: "Contact Closes", required: false, multiple: true
			ifUnset "acceleration", "capability.accelerationSensor", title: "Acceleration Detected", required: false, multiple: true
			ifUnset "mySwitch", "capability.switch", title: "Switch Turned On", required: false, multiple: true
			ifUnset "mySwitchOff", "capability.switch", title: "Switch Turned Off", required: false, multiple: true
			ifUnset "arrivalPresence", "capability.presenceSensor", title: "Arrival Of", required: false, multiple: true
			ifUnset "departurePresence", "capability.presenceSensor", title: "Departure Of", required: false, multiple: true
			ifUnset "smoke", "capability.smokeDetector", title: "Smoke Detected", required: false, multiple: true
			ifUnset "water", "capability.waterSensor", title: "Water Sensor Wet", required: false, multiple: true
			ifUnset "temperature", "capability.temperatureMeasurement", title: "Temperature", required: false, multiple: true
            ifUnset "signalStrength", "capability.signalStrength", title: "Signal Strength", required: false, multiple: true
            ifUnset "powerMeter", "capability.powerMeter", title: "Power Meter", required: false, multiple: true
            ifUnset "energyMeter", "capability.energyMeter", title: "Energy Meter", required: false, multiple: true
			ifUnset "button1", "capability.button", title: "Button Press", required:false, multiple:true //remove from production
			ifUnset "triggerModes", "mode", title: "System Changes Mode", description: "Select mode(s)", required: false, multiple: true
		}
    }
}

private anythingSet() {
	for (name in ["motion","contact","contactClosed","acceleration","mySwitch","mySwitchOff","arrivalPresence","departurePresence","smoke","water", "temperature","signalStrength","powerMeter","energyMeter","button1","timeOfDay","triggerModes","timeOfDay"]) {
		if (settings[name]) {
			return true
		}
	}
	return false
}

private ifUnset(Map options, String name, String capability) {
	if (!settings[name]) {
		input(options, name, capability)
	}
}

private ifSet(Map options, String name, String capability) {
	if (settings[name]) {
		input(options, name, capability)
	}
}

def subscribeToEvents() {
	//subscribe(app, appTouchHandler)
	subscribe(contact, "contact.open", eventHandler)
	subscribe(contactClosed, "contact.closed", eventHandler)
	subscribe(acceleration, "acceleration.active", eventHandler)
	subscribe(motion, "motion.active", eventHandler)
	subscribe(mySwitch, "switch.on", eventHandler)
	subscribe(mySwitchOff, "switch.off", eventHandler)
	subscribe(arrivalPresence, "presence.present", eventHandler)
	subscribe(departurePresence, "presence.not present", eventHandler)
	subscribe(smoke, "smoke.detected", eventHandler)
	subscribe(smoke, "smoke.tested", eventHandler)
	subscribe(smoke, "carbonMonoxide.detected", eventHandler)
	subscribe(water, "water.wet", eventHandler)
    subscribe(temperature, "temperature", eventHandler)
    subscribe(powerMeter, "power", eventHandler)
	subscribe(energyMeter, "energy", eventHandler)
    subscribe(signalStrength, "lqi", eventHandler)
    subscribe(signalStrength, "rssi", eventHandler)
	subscribe(button1, "button.pushed", eventHandler)
	if (triggerModes) {
		subscribe(location, modeChangeHandler)
	}
}

def eventHandler(evt) {
    takeAction(evt)
}

def modeChangeHandler(evt) {
	if (evt.value in triggerModes) {
		eventHandler(evt)
	}
}

private scheduleActions() {
    def minutes = Math.max(settings.interval.toInteger(),1)
    def cron = "0 0/${minutes} * * * ?"
   	schedule(cron, scheduledActionsHandler)
}

def scheduledActionsHandler() {
    state.actionTime = new Date().time
    refreshDevices()
    discoverAllWemoTypes()
}

def resubscribe() {
	refresh()
}

def refreshDevices() {
	def devices = getAllChildDevices()
	devices.each { d ->
		log.debug "Calling refresh() on device: ${d.id}"
		d.refresh()
	}
}

def subscribeToDevices() {
	def devices = getAllChildDevices()
	devices.each { d ->
		d.subscribe()
	}
}

def appTouchHandler(evt) {
	takeAction(evt)
}

private takeAction(evt) {
	def eventTime = new Date().time
	if (eventTime > ( 60000 + Math.max((settings.interval ? settings.interval.toInteger():0),3) * 1000 * 60 + (state.actionTime?:0))) {
		scheduledActionsHandler()
	}
}

private def parseXmlBody(def body) {
	def decodedBytes = body.decodeBase64()
	def bodyString
	try {
		bodyString = new String(decodedBytes)
	} catch (Exception e) {
		// Keep this log for debugging StringIndexOutOfBoundsException issue
		throw e
	}
	return new XmlSlurper().parseText(bodyString)
}

def pollChildren() {
	def devices = getAllChildDevices()
	devices.each { d ->
		//only poll switches?
		d.poll()
	}
}

def delayPoll() {
	runIn(5, "pollChildren")
}

private logger(msg, level = "debug") {

    switch(level) {
        case "error":
            if (state.loggingLevelIDE >= 1) log.error msg
            break

        case "warn":
            if (state.loggingLevelIDE >= 2) log.warn msg
            break

        case "info":
            if (state.loggingLevelIDE >= 3) log.info msg
            break

        case "debug":
            if (state.loggingLevelIDE >= 4) log.debug msg
            break

        case "trace":
            if (state.loggingLevelIDE >= 5) log.trace msg
            break

        default:
            log.debug msg
            break
    }
}

private Integer convertHexToInt(hex) {
	Integer.parseInt(hex,16)
}

private String convertHexToIP(hex) {
	[convertHexToInt(hex[0..1]),convertHexToInt(hex[2..3]),convertHexToInt(hex[4..5]),convertHexToInt(hex[6..7])].join(".")
}

private Boolean canInstallLabs() {
	return true //hasAllHubsOver("000.011.00603")
}

private Boolean hasAllHubsOver(String desiredFirmware) {
	return realHubFirmwareVersions.every { fw -> fw >= desiredFirmware }
}

private List getRealHubFirmwareVersions() {
	return location.hubs*.firmwareVersionString.findAll { it }
}
