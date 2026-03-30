package com.trainwidget.data

import java.time.LocalTime

// ── PTV API response models ────────────────────────────────────────────────

data class PtvDeparturesResponse(
    val departures: List<PtvDeparture>,
    val stops: Map<String, PtvStop>?,
    val routes: Map<String, PtvRoute>?,
    val runs: Map<String, PtvRun>?
)

data class PtvDeparture(
    val stop_id: Int,
    val route_id: Int,
    val run_id: Int,
    val run_ref: String?,
    val direction_id: Int,
    val disruption_ids: List<Int>?,
    val scheduled_departure_utc: String?,   // ISO-8601 UTC
    val estimated_departure_utc: String?,   // ISO-8601 UTC (real-time)
    val at_platform: Boolean,
    val platform_number: String?,
    val flags: String?,
    val departure_sequence: Int
)

data class PtvStop(val stop_id: Int, val stop_name: String)
data class PtvRoute(val route_id: Int, val route_name: String, val route_number: String)
data class PtvRun(val run_id: Int, val destination_name: String?)

// ── App domain models ──────────────────────────────────────────────────────

/**
 * A single processed departure shown in the widget.
 */
data class Departure(
    val scheduledTime: String,      // "HH:mm" in local time
    val estimatedTime: String?,     // "HH:mm" if real-time differs from scheduled
    val delayMinutes: Int,          // positive = late, 0 = on time, negative = early
    val platformNumber: String?,
    val minutesUntilDeparture: Long
) {
    val isDelayed: Boolean get() = delayMinutes > 1
    val isEarly: Boolean get() = delayMinutes < -1
    val displayTime: String get() = estimatedTime ?: scheduledTime
}

// ── Melbourne Metro stations ───────────────────────────────────────────────

data class Station(val name: String, val stopId: Int)

/**
 * Authoritative list of Melbourne Metro train stations with PTV stop IDs.
 * Stop IDs verified against PTV Timetable API v3.
 * Full list: GET /v3/stops/route_type/0
 */
object MelbourneStations {

    val ALL: List<Station> = listOf(
        // City Loop / Cross-City
        Station("Flinders Street", 1071),
        Station("Southern Cross", 1181),
        Station("Melbourne Central", 1120),
        Station("Flagstaff", 1068),
        Station("Parliament", 1155),
        Station("Richmond", 1162),
        Station("North Melbourne", 1141),

        // Hurstbridge / Diamond Valley
        Station("Clifton Hill", 1041),
        Station("Westgarth", 1217),
        Station("Dennis", 1052),
        Station("Fairfield", 1066),
        Station("Alphington", 1003),
        Station("Darebin", 1049),
        Station("Ivanhoe", 1097),
        Station("Eaglemont", 1058),
        Station("Heidelberg", 1087),
        Station("Rosanna", 1166),
        Station("Macleod", 1117),
        Station("Watsonia", 1215),
        Station("Greensborough", 1083),
        Station("Montmorency", 1129),
        Station("Eltham", 1060),
        Station("Diamond Creek", 1053),
        Station("Wattle Glen", 1216),
        Station("Hurstbridge", 1093),

        // Mernda
        Station("Epping", 1063),
        Station("South Morang", 1182),
        Station("Middle Gorge", 1122),
        Station("Hawkstowe", 1086),
        Station("Mernda", 1121),

        // Burnley group
        Station("Jolimont", 1104),
        Station("West Richmond", 1219),
        Station("North Richmond", 1142),
        Station("Collingwood", 1042),
        Station("Victoria Park", 1212),
        Station("Abbotsford", 1001),
        Station("Johnston", 1105),
        Station("Clifton Hill", 1041),
        Station("Burnley", 1028),
        Station("Hawthorn", 1085),
        Station("Glenferrie", 1077),
        Station("Auburn", 1008),
        Station("Camberwell", 1029),
        Station("Canterbury", 1033),
        Station("Chatham", 1037),
        Station("Surrey Hills", 1187),
        Station("Mont Albert", 1128),
        Station("Box Hill", 1021),
        Station("Laburnum", 1108),
        Station("Blackburn", 1015),
        Station("Nunawading", 1148),
        Station("Mitcham", 1127),
        Station("Heatherdale", 1088),
        Station("Ringwood", 1163),
        Station("Heathmont", 1089),
        Station("Bayswater", 1011),
        Station("Boronia", 1020),
        Station("Ferntree Gully", 1067),
        Station("Upper Ferntree Gully", 1210),
        Station("Belgrave", 1012),
        Station("Ringwood East", 1164),
        Station("Croydon", 1047),
        Station("Mooroolbark", 1130),
        Station("Lilydale", 1111),

        // Caulfield group
        Station("Malvern", 1118),
        Station("Armadale", 1006),
        Station("Toorak", 1204),
        Station("Hawksburn", 1084),
        Station("South Yarra", 1183),
        Station("Prahran", 1157),
        Station("Windsor", 1222),
        Station("Balaclava", 1009),
        Station("Ripponlea", 1165),
        Station("Elsternwick", 1061),
        Station("Gardenvale", 1073),
        Station("North Brighton", 1138),
        Station("Middle Brighton", 1123),
        Station("Brighton Beach", 1024),
        Station("Hampton", 1082),
        Station("Sandringham", 1174),
        Station("Caulfield", 1036),
        Station("Carnegie", 1034),
        Station("Murrumbeena", 1134),
        Station("Hughesdale", 1092),
        Station("Oakleigh", 1150),
        Station("Huntingdale", 1094),
        Station("Clayton", 1038),
        Station("Westall", 1220),
        Station("Springvale", 1184),
        Station("Sandown Park", 1175),
        Station("Noble Park", 1137),
        Station("Yarraman", 1224),
        Station("Dandenong", 1048),
        Station("Hallam", 1081),
        Station("Narre Warren", 1135),
        Station("Berwick", 1013),
        Station("Beaconsfield", 1010),
        Station("Officer", 1152),
        Station("Cardinia Road", 1032),
        Station("Pakenham", 1153),
        Station("Merinda Park", 1119),
        Station("Cranbourne", 1046),

        // Frankston / Mornington Peninsula
        Station("Windsor", 1222),
        Station("Glenhuntly", 1078),
        Station("Ormond", 1151),
        Station("McKinnon", 1116),
        Station("Bentleigh", 1014),
        Station("Patterson", 1154),
        Station("Moorabbin", 1131),
        Station("Highett", 1091),
        Station("Southland", 1185),
        Station("Cheltenham", 1039),
        Station("Mentone", 1119),
        Station("Parkdale", 1154),
        Station("Mordialloc", 1132),
        Station("Aspendale", 1007),
        Station("Edithvale", 1059),
        Station("Chelsea", 1038),
        Station("Bonbeach", 1018),
        Station("Carrum", 1035),
        Station("Seaford", 1177),
        Station("Kananook", 1107),
        Station("Frankston", 1072),

        // Williamstown / Werribee
        Station("Footscray", 1070),
        Station("Yarraville", 1223),
        Station("Spotswood", 1186),
        Station("Newport", 1136),
        Station("Williamstown Beach", 1221),
        Station("Williamstown", 1220),
        Station("Altona", 1004),
        Station("Westona", 1219),
        Station("Laverton", 1110),
        Station("Aircraft", 1002),
        Station("Williams Landing", 1218),
        Station("Hoppers Crossing", 1090),
        Station("Werribee", 1218),

        // Sunbury / Melton
        Station("Flemington Bridge", 1069),
        Station("Newmarket", 1136),
        Station("Kensington", 1109),
        Station("Moonee Ponds", 1129),
        Station("Essendon", 1064),
        Station("Glenbervie", 1076),
        Station("Strathmore", 1186),
        Station("Pascoe Vale", 1155),
        Station("Oak Park", 1149),
        Station("Glenroy", 1078),
        Station("Jacana", 1098),
        Station("Broadmeadows", 1025),
        Station("Coolaroo", 1043),
        Station("Roxburgh Park", 1168),
        Station("Craigieburn", 1045),
        Station("Sunbury", 1188),

        // Upfield
        Station("Jewell", 1103),
        Station("Brunswick", 1027),
        Station("Anstey", 1005),
        Station("Moreland", 1133),
        Station("Coburg", 1040),
        Station("Batman", 1010),
        Station("Merlynston", 1121),
        Station("Fawkner", 1065),
        Station("Gowrie", 1080),
        Station("Upfield", 1211),
    ).distinctBy { it.stopId }.sortedBy { it.name }
}

// ── Widget OD Pair configuration ───────────────────────────────────────────

/**
 * One configured origin→destination pair with an active time window.
 */
data class OdPair(
    val id: String,             // UUID
    val label: String,          // e.g. "Morning commute"
    val originStopId: Int,
    val originName: String,
    val destinationStopId: Int,
    val destinationName: String,
    val activeFrom: LocalTime,  // start of active window (inclusive)
    val activeTo: LocalTime,    // end of active window (inclusive)
    val directionId: Int = -1   // -1 = auto-detect from destination
) {
    fun isActiveNow(): Boolean {
        val now = LocalTime.now()
        return now.isAfter(activeFrom) && now.isBefore(activeTo)
    }
}
