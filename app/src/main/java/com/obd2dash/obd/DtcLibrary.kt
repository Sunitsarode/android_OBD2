package com.obd2dash.obd

/**
 * Descriptions for the SAE generic trouble codes.
 *
 * Manufacturer-specific codes (P1xxx, and the P3xxx range) are not standardised,
 * so those fall back to a description of the code family rather than guessing.
 */
object DtcLibrary {

    fun describe(code: String): String {
        MAP[code]?.let { return it }
        if (code.length != 5) return "Unknown code"
        val letter = code[0]
        val second = code[1]
        val generic = second == '0' || second == '2'
        val kind = when (letter) {
            'P' -> "powertrain (engine/transmission)"
            'C' -> "chassis (ABS/suspension/steering)"
            'B' -> "body (airbag/lighting/comfort)"
            'U' -> "network (CAN bus/module communication)"
            else -> "unknown system"
        }
        return if (generic) "Generic $kind fault - description not in library"
        else "Manufacturer-specific $kind code - see Maruti Suzuki service data"
    }

    /** True when the code is defined by SAE rather than by the manufacturer. */
    fun isGeneric(code: String): Boolean =
        code.length == 5 && (code[1] == '0' || code[1] == '2')

    private val MAP = HashMap<String, String>(512)

    private fun put(vararg pairs: Pair<String, String>) {
        pairs.forEach { MAP[it.first] = it.second }
    }

    // --- Fuel and air metering ---
    init {
        put(
            "P0100" to "Mass air flow (MAF) circuit malfunction",
            "P0101" to "MAF circuit range/performance",
            "P0102" to "MAF circuit low input",
            "P0103" to "MAF circuit high input",
            "P0104" to "MAF circuit intermittent",
            "P0105" to "Manifold absolute pressure (MAP) circuit malfunction",
            "P0106" to "MAP/barometric pressure range/performance",
            "P0107" to "MAP/barometric pressure low input",
            "P0108" to "MAP/barometric pressure high input",
            "P0110" to "Intake air temperature circuit malfunction",
            "P0111" to "Intake air temperature range/performance",
            "P0112" to "Intake air temperature low input",
            "P0113" to "Intake air temperature high input",
            "P0115" to "Engine coolant temperature circuit malfunction",
            "P0116" to "Engine coolant temperature range/performance",
            "P0117" to "Engine coolant temperature low input",
            "P0118" to "Engine coolant temperature high input",
            "P0120" to "Throttle/pedal position sensor A circuit",
            "P0121" to "Throttle position sensor A range/performance",
            "P0122" to "Throttle position sensor A low input",
            "P0123" to "Throttle position sensor A high input",
            "P0125" to "Coolant temperature too low for closed loop fuel control",
            "P0128" to "Coolant thermostat below regulating temperature",
            "P0130" to "O2 sensor circuit bank 1 sensor 1",
            "P0131" to "O2 sensor low voltage bank 1 sensor 1",
            "P0132" to "O2 sensor high voltage bank 1 sensor 1",
            "P0133" to "O2 sensor slow response bank 1 sensor 1",
            "P0134" to "O2 sensor no activity detected bank 1 sensor 1",
            "P0135" to "O2 sensor heater circuit bank 1 sensor 1",
            "P0136" to "O2 sensor circuit bank 1 sensor 2",
            "P0137" to "O2 sensor low voltage bank 1 sensor 2",
            "P0138" to "O2 sensor high voltage bank 1 sensor 2",
            "P0139" to "O2 sensor slow response bank 1 sensor 2",
            "P0140" to "O2 sensor no activity detected bank 1 sensor 2",
            "P0141" to "O2 sensor heater circuit bank 1 sensor 2",
            "P0150" to "O2 sensor circuit bank 2 sensor 1",
            "P0155" to "O2 sensor heater circuit bank 2 sensor 1",
            "P0170" to "Fuel trim malfunction bank 1",
            "P0171" to "System too lean bank 1",
            "P0172" to "System too rich bank 1",
            "P0174" to "System too lean bank 2",
            "P0175" to "System too rich bank 2",
            "P0190" to "Fuel rail pressure sensor circuit",
            "P0191" to "Fuel rail pressure sensor range/performance",
            "P0192" to "Fuel rail pressure sensor low input",
            "P0193" to "Fuel rail pressure sensor high input"
        )
    }

    // --- Injectors, boost, misfire, ignition ---
    init {
        put(
            "P0200" to "Injector circuit malfunction",
            "P0201" to "Injector circuit open cylinder 1",
            "P0202" to "Injector circuit open cylinder 2",
            "P0203" to "Injector circuit open cylinder 3",
            "P0204" to "Injector circuit open cylinder 4",
            "P0217" to "Engine over-temperature condition",
            "P0219" to "Engine over-speed condition",
            "P0220" to "Throttle/pedal position sensor B circuit",
            "P0222" to "Throttle position sensor B low input",
            "P0223" to "Throttle position sensor B high input",
            "P0230" to "Fuel pump primary circuit",
            "P0234" to "Turbocharger/supercharger overboost",
            "P0243" to "Wastegate solenoid A malfunction",
            "P0261" to "Cylinder 1 injector circuit low",
            "P0262" to "Cylinder 1 injector circuit high",
            "P0264" to "Cylinder 2 injector circuit low",
            "P0265" to "Cylinder 2 injector circuit high",
            "P0267" to "Cylinder 3 injector circuit low",
            "P0268" to "Cylinder 3 injector circuit high",
            "P0270" to "Cylinder 4 injector circuit low",
            "P0271" to "Cylinder 4 injector circuit high",
            "P0299" to "Turbocharger/supercharger underboost",
            "P0300" to "Random/multiple cylinder misfire detected",
            "P0301" to "Cylinder 1 misfire detected",
            "P0302" to "Cylinder 2 misfire detected",
            "P0303" to "Cylinder 3 misfire detected",
            "P0304" to "Cylinder 4 misfire detected",
            "P0305" to "Cylinder 5 misfire detected",
            "P0306" to "Cylinder 6 misfire detected",
            "P0315" to "Crankshaft position system variation not learned",
            "P0320" to "Ignition/distributor engine speed input circuit",
            "P0325" to "Knock sensor 1 circuit bank 1",
            "P0326" to "Knock sensor 1 range/performance bank 1",
            "P0327" to "Knock sensor 1 low input bank 1",
            "P0328" to "Knock sensor 1 high input bank 1",
            "P0335" to "Crankshaft position sensor A circuit",
            "P0336" to "Crankshaft position sensor A range/performance",
            "P0337" to "Crankshaft position sensor A low input",
            "P0338" to "Crankshaft position sensor A high input",
            "P0340" to "Camshaft position sensor A circuit bank 1",
            "P0341" to "Camshaft position sensor A range/performance",
            "P0342" to "Camshaft position sensor A low input",
            "P0343" to "Camshaft position sensor A high input",
            "P0351" to "Ignition coil A primary/secondary circuit",
            "P0352" to "Ignition coil B primary/secondary circuit",
            "P0353" to "Ignition coil C primary/secondary circuit",
            "P0354" to "Ignition coil D primary/secondary circuit"
        )
    }

    // --- Emission control and auxiliary systems ---
    init {
        put(
            "P0401" to "EGR flow insufficient detected",
            "P0402" to "EGR flow excessive detected",
            "P0403" to "EGR circuit malfunction",
            "P0404" to "EGR circuit range/performance",
            "P0405" to "EGR sensor A circuit low",
            "P0406" to "EGR sensor A circuit high",
            "P0410" to "Secondary air injection system malfunction",
            "P0411" to "Secondary air injection incorrect flow detected",
            "P0420" to "Catalyst system efficiency below threshold bank 1",
            "P0430" to "Catalyst system efficiency below threshold bank 2",
            "P0440" to "Evaporative emission control system malfunction",
            "P0441" to "Evaporative emission system incorrect purge flow",
            "P0442" to "Evaporative emission system leak detected (small leak)",
            "P0443" to "Evaporative purge control valve circuit",
            "P0446" to "Evaporative vent control circuit malfunction",
            "P0447" to "Evaporative vent control circuit open",
            "P0448" to "Evaporative vent control circuit shorted",
            "P0451" to "Evaporative pressure sensor range/performance",
            "P0452" to "Evaporative pressure sensor low input",
            "P0453" to "Evaporative pressure sensor high input",
            "P0455" to "Evaporative emission system leak detected (gross leak)",
            "P0456" to "Evaporative emission system leak detected (very small leak)",
            "P0457" to "Evaporative leak detected - fuel cap loose or missing",
            "P0460" to "Fuel level sensor circuit malfunction",
            "P0461" to "Fuel level sensor range/performance",
            "P0462" to "Fuel level sensor low input",
            "P0463" to "Fuel level sensor high input",
            "P0480" to "Cooling fan 1 control circuit",
            "P0481" to "Cooling fan 2 control circuit",
            "P0500" to "Vehicle speed sensor malfunction",
            "P0501" to "Vehicle speed sensor range/performance",
            "P0502" to "Vehicle speed sensor low input",
            "P0505" to "Idle air control system malfunction",
            "P0506" to "Idle control system RPM lower than expected",
            "P0507" to "Idle control system RPM higher than expected",
            "P0520" to "Engine oil pressure sensor/switch circuit",
            "P0521" to "Engine oil pressure sensor range/performance",
            "P0522" to "Engine oil pressure sensor low voltage",
            "P0532" to "A/C refrigerant pressure sensor low input",
            "P0562" to "System voltage low",
            "P0563" to "System voltage high",
            "P0571" to "Brake switch A circuit malfunction"
        )
    }

    // --- Control module, transmission, P2xxx, and network codes ---
    init {
        put(
            "P0600" to "Serial communication link malfunction",
            "P0601" to "Internal control module memory checksum error",
            "P0602" to "Control module programming error",
            "P0603" to "Internal control module keep-alive memory error",
            "P0604" to "Internal control module RAM error",
            "P0605" to "Internal control module ROM error",
            "P0606" to "ECM/PCM processor fault",
            "P0607" to "Control module performance",
            "P0620" to "Generator control circuit malfunction",
            "P0627" to "Fuel pump A control circuit open",
            "P0645" to "A/C clutch relay control circuit",
            "P0650" to "Malfunction indicator lamp control circuit",
            "P0685" to "ECM/PCM power relay control circuit open",
            "P0700" to "Transmission control system malfunction (MIL request)",
            "P0701" to "Transmission control system range/performance",
            "P0702" to "Transmission control system electrical",
            "P0705" to "Transmission range sensor circuit (PRNDL input)",
            "P0710" to "Transmission fluid temperature sensor circuit",
            "P0711" to "Transmission fluid temperature range/performance",
            "P0715" to "Input/turbine speed sensor circuit",
            "P0717" to "Input/turbine speed sensor no signal",
            "P0720" to "Output speed sensor circuit malfunction",
            "P0725" to "Engine speed input circuit malfunction",
            "P0730" to "Incorrect gear ratio",
            "P0731" to "Gear 1 incorrect ratio",
            "P0732" to "Gear 2 incorrect ratio",
            "P0733" to "Gear 3 incorrect ratio",
            "P0734" to "Gear 4 incorrect ratio",
            "P0740" to "Torque converter clutch circuit malfunction",
            "P0741" to "Torque converter clutch performance or stuck off",
            "P0742" to "Torque converter clutch stuck on",
            "P0750" to "Shift solenoid A malfunction",
            "P0753" to "Shift solenoid A electrical",
            "P0755" to "Shift solenoid B malfunction",
            "P0758" to "Shift solenoid B electrical",
            "P2096" to "Post catalyst fuel trim system too lean bank 1",
            "P2097" to "Post catalyst fuel trim system too rich bank 1",
            "P2101" to "Throttle actuator control motor circuit range/performance",
            "P2119" to "Throttle actuator control throttle body range/performance",
            "P2135" to "Throttle/pedal position sensor A/B voltage correlation",
            "P2138" to "Pedal position sensor D/E voltage correlation",
            "P2187" to "System too lean at idle bank 1",
            "P2188" to "System too rich at idle bank 1",
            "P2195" to "O2 sensor signal stuck lean bank 1 sensor 1",
            "P2196" to "O2 sensor signal stuck rich bank 1 sensor 1",
            "P2279" to "Intake air system leak",
            "P2413" to "EGR system performance",
            "U0001" to "High speed CAN communication bus",
            "U0100" to "Lost communication with ECM/PCM A",
            "U0101" to "Lost communication with transmission control module",
            "U0121" to "Lost communication with ABS control module",
            "U0140" to "Lost communication with body control module",
            "U0155" to "Lost communication with instrument panel cluster",
            "U0401" to "Invalid data received from ECM/PCM A",
            "C0035" to "Left front wheel speed sensor circuit",
            "C0040" to "Right front wheel speed sensor circuit",
            "C0045" to "Left rear wheel speed sensor circuit",
            "C0050" to "Right rear wheel speed sensor circuit",
            "B0001" to "Driver frontal stage 1 deployment control",
            "B0010" to "Passenger frontal stage 1 deployment control"
        )
    }
}
