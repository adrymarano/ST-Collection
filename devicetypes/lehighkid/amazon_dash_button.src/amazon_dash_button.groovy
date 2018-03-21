import groovy.json.JsonSlurper

metadata {
	definition (name: "Amazon Dash Button", namespace: "lehighkid", author: "erocm123") {
		capability "Button"
		capability "Actuator"
		capability "Switch"
		capability "Momentary"
		capability "Sensor"
	}

	// UI tile definitions
	tiles {
		standardTile("button", "device.button", canChangeIcon: true, inactiveLabel: false, width: 2, height: 2) {
			state "default", label: '', icon: "st.secondary.off", action: "button.push"
		}
		main "button"
		details(["button"])
	}
}


def parse(description) {
	state.msgReceived = now() 
    //check for repeat requests
    if (state.msgReceived - state.msgLastReceived < 2500) {
    	log.debug "dupped request ignored ${state.msgReceived} - ${state.msgLastReceived}"
    	return
    }
    def events = []
    def descMap = parseDescriptionAsMap(description)
    def body = new String(descMap["body"].decodeBase64())
    def slurper = new JsonSlurper()
    def result = slurper.parseText(body)

    if (result.containsKey("message")) {
       //events << createEvent(name:"hubInfo", value:result.message)
       push()
    }
    state.msgLastReceived = state.msgReceived
    return events
}

def parseDescriptionAsMap(description) {
	description.split(",").inject([:]) { map, param ->
		def nameAndValue = param.split(":")
        
        if (nameAndValue.length == 2) map += [(nameAndValue[0].trim()):nameAndValue[1].trim()]
        else map += [(nameAndValue[0].trim()):""]
	}
}

def push() {
	log.debug "Executing 'push'"
	sendEvent(name: "switch", value: "on", isStateChange: true, displayed: false)
	sendEvent(name: "switch", value: "off", isStateChange: true, displayed: false)
	sendEvent(name: "momentary", value: "pushed", isStateChange: true)
    sendEvent(name: "button", value: "pushed", data: [buttonNumber: 1], descriptionText: "$device.displayName button 1 was pressed", isStateChange: true)
}

def on() {
	push()
}

def off() {
	push()
}
