package com.example.policesimulator

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.random.Random
import kotlinx.coroutines.delay

// ==========================================
// ENUMS & DATA CLASSES (Menu System)
// ==========================================

enum class GameScreen { MAIN_MENU, CHARACTER_CREATOR, OPEN_WORLD, DRIVING_WORLD }

enum class Rank(val title: String, val xpRequired: Int) {
CADET("Cadet", 0),
OFFICER("Officer", 100),
SERGEANT("Sergeant", 250),
LIEUTENANT("Lieutenant", 500),
CAPTAIN("Captain", 1000)
}

data class CruiserVehicle(
val name: String,
val icon: String,
val topSpeed: Int,
val efficiency: Float,
val minRank: Rank
)

data class OfficerProfile(
val name: String = "Officer",
val age: Int = 25,
val skinIndex: Int = 0,
val rank: Rank = Rank.CADET,
val xp: Int = 0,
val chosenVehicleIndex: Int = 0,
val money: Int = 0,
val arrests: Int = 0,
val patrolsCompleted: Int = 0
)

data class PatrolResult(
val message: String,
val xpGained: Int,
val moneyGained: Int,
val arrests: Int,
val rankUp: Boolean,
val newRank: Rank,
val promotionBonus: Int = 0
)

data class TrafficStopResult(
val violation: String,
val actionTaken: String,
val message: String,
val xpGained: Int,
val moneyGained: Int,
val arrestsMade: Int,
val rankUp: Boolean,
val newRank: Rank,
val promotionBonus: Int = 0
)

data class VipEscortResult(
val success: Boolean,
val message: String,
val xpGained: Int,
val moneyGained: Int,
val rankUp: Boolean,
val newRank: Rank,
val promotionBonus: Int = 0
)

val VEHICLE_CATALOG = listOf(
CruiserVehicle("Standard Interceptor", "🚓", 110, 18.5f, Rank.CADET),
CruiserVehicle("Undercover Car", "🚗", 125, 22.0f, Rank.SERGEANT),
CruiserVehicle("SWAT Van", "🚐", 90, 12.0f, Rank.LIEUTENANT)
)

val SKIN_PALETTE = listOf(
Color(0xFFFFE0BD),
Color(0xFFF5CBA7),
Color(0xFFD2B48C),
Color(0xFF4A3728)
)

// ==========================================
// WORLD & DRIVING CLASSES
// ==========================================

data class Camera(
var x: Float = 0f,
var y: Float = 0f,
var zoom: Float = 1f
) {
fun follow(carX: Float, carY: Float, screenWidth: Float, screenHeight: Float) {
x = carX - screenWidth / 2
y = carY - screenHeight / 2
}

fun shake(intensity: Float = 10f) {
x += (Math.random().toFloat() - 0.5f) * intensity
y += (Math.random().toFloat() - 0.5f) * intensity
}
}

enum class Tile(val color: Color) {
GRASS(Color(0xFF4CAF50)),
ROAD(Color(0xFF333333)),
SIDEWALK(Color(0xFFAAAAAA)),
BUILDING(Color(0xFF666666))
}

object WorldMap {
const val WIDTH = 5000f
const val HEIGHT = 5000f
const val TILE_SIZE = 100f

val tiles: Array<Array<Tile>> by lazy {
val cols = (WIDTH / TILE_SIZE).toInt()
val rows = (HEIGHT / TILE_SIZE).toInt()
Array(cols) { x ->
Array(rows) { y ->
when {
x in 20..30 && y in 22..28 -> Tile.ROAD
y in 20..30 && x in 22..28 -> Tile.ROAD
(x in 18..22 && y in 18..22) -> Tile.BUILDING
else -> Tile.GRASS
}
}
}
}

fun isSolidTile(worldX: Float, worldY: Float): Boolean {
val tx = (worldX / TILE_SIZE).toInt()
val ty = (worldY / TILE_SIZE).toInt()
return if (tx in tiles.indices && ty in tiles[0].indices) {
tiles[tx][ty] == Tile.BUILDING
} else true
}
}

data class PoliceCar(
var x: Float = 1500f,
var y: Float = 1500f,
var angle: Float = 0f,
var speed: Float = 0f,
val maxSpeed: Float = 20f,
val acceleration: Float = 12f,
val brakeDecel: Float = 18f,
val turnSpeed: Float = 120f
) {
var accelerating by mutableStateOf(false)
var braking by mutableStateOf(false)
var turningLeft by mutableStateOf(false)
var turningRight by mutableStateOf(false)

fun update(dt: Float) {
when {
accelerating -> speed += acceleration * dt
braking -> speed -= brakeDecel * dt
}
speed *= (1f - 3f * dt).coerceIn(0f, 1f)
speed = speed.coerceIn(-maxSpeed * 0.5f, maxSpeed)

if (kotlin.math.abs(speed) > 0.5f) {
val turn = turnSpeed * dt * (speed / maxSpeed)
if (turningLeft) angle -= turn
if (turningRight) angle += turn
}

val rad = Math.toRadians(angle.toDouble())
x += kotlin.math.cos(rad).toFloat() * speed
y += kotlin.math.sin(rad).toFloat() * speed

if (WorldMap.isSolidTile(x, y)) {
x -= kotlin.math.cos(rad).toFloat() * speed
y -= kotlin.math.sin(rad).toFloat() * speed
speed *= -0.2f
}

x = x.coerceIn(0f, WorldMap.WIDTH)
y = y.coerceIn(0f, WorldMap.HEIGHT)
}
}

object Renderer {
fun drawWorld(drawScope: DrawScope, camera: Camera) {
with(drawScope) {
val left = camera.x
val top = camera.y
val right = camera.x + size.width
val bottom = camera.y + size.height

val startCol = (left / WorldMap.TILE_SIZE).toInt().coerceAtLeast(0)
val endCol = (right / WorldMap.TILE_SIZE).toInt().coerceAtMost(WorldMap.tiles.size - 1)
val startRow = (top / WorldMap.TILE_SIZE).toInt().coerceAtLeast(0)
val endRow = (bottom / WorldMap.TILE_SIZE).toInt().coerceAtMost(WorldMap.tiles[0].size - 1)

for (col in startCol..endCol) {
for (row in startRow..endRow) {
val tile = WorldMap.tiles[col][row]
val x = col * WorldMap.TILE_SIZE - camera.x
val y = row * WorldMap.TILE_SIZE - camera.y
drawRect(tile.color, Offset(x, y), Size(WorldMap.TILE_SIZE, WorldMap.TILE_SIZE))
}
}
}
}

fun drawPoliceCar(drawScope: DrawScope, car: PoliceCar, camera: Camera) {
with(drawScope) {
val screenX = car.x - camera.x
val screenY = car.y - camera.y
rotate(car.angle, Offset(screenX, screenY)) {
drawRect(Color.Red, Offset(screenX - 15f, screenY - 10f), Size(30f, 20f))
}
}
}
}

@Composable
fun DrivingControls(
onAccelerate: (Boolean) -> Unit,
onBrake: (Boolean) -> Unit,
onTurnLeft: (Boolean) -> Unit,
onTurnRight: (Boolean) -> Unit,
modifier: Modifier = Modifier
) {
Row(
modifier = modifier.fillMaxWidth().padding(16.dp),
horizontalArrangement = Arrangement.SpaceEvenly
) {
Box(
modifier = Modifier.size(80.dp).background(Color.Gray).pointerInput(Unit) {
detectTapGestures(onPress = {
onTurnLeft(true)
tryAwaitRelease()
onTurnLeft(false)
})
},
contentAlignment = Alignment.Center
) { Text("◀", fontSize = 24.sp, color = Color.White) }

Box(
modifier = Modifier.size(80.dp).background(Color.Green).pointerInput(Unit) {
detectTapGestures(onPress = {
onAccelerate(true)
tryAwaitRelease()
onAccelerate(false)
})
},
contentAlignment = Alignment.Center
) { Text("⬆", fontSize = 24.sp, color = Color.White) }

Box(
modifier = Modifier.size(80.dp).background(Color.Red).pointerInput(Unit) {
detectTapGestures(onPress = {
onBrake(true)
tryAwaitRelease()
onBrake(false)
})
},
contentAlignment = Alignment.Center
) { Text("⬇", fontSize = 24.sp, color = Color.White) }

Box(
modifier = Modifier.size(80.dp).background(Color.Gray).pointerInput(Unit) {
detectTapGestures(onPress = {
onTurnRight(true)
tryAwaitRelease()
onTurnRight(false)
})
},
contentAlignment = Alignment.Center
) { Text("▶", fontSize = 24.sp, color = Color.White) }
}
}

@Composable
fun WorldScreen(onBack: () -> Unit) {
val car = remember { PoliceCar() }
val camera = remember { Camera() }
val config = LocalConfiguration.current
val screenWidth = config.screenWidthDp.dp.value
val screenHeight = config.screenHeightDp.dp.value

LaunchedEffect(Unit) {
var lastTime = System.nanoTime()
while (true) {
val now = System.nanoTime()
val dt = (now - lastTime) / 1_000_000_000f
lastTime = now
car.update(dt)
camera.follow(car.x, car.y, screenWidth, screenHeight)
delay(16)
}
}

Box(modifier = Modifier.fillMaxSize()) {
Canvas(modifier = Modifier.fillMaxSize()) {
Renderer.drawWorld(this, camera)
Renderer.drawPoliceCar(this, car, camera)
}
Column(modifier = Modifier.fillMaxSize()) {
Text(text = "Speed: $`{(car.speed * 3.6).toInt()} km/h", color = Color.White, modifier = Modifier.padding(16.dp))
Spacer(modifier = Modifier.weight(1f))
DrivingControls(
onAccelerate = { car.accelerating = it },
onBrake = { car.braking = it },
onTurnLeft = { car.turningLeft = it },
onTurnRight = { car.turningRight = it }
)
Button(onClick = onBack, modifier = Modifier.padding(16.dp).fillMaxWidth()) { Text("Exit to Menu") }
}
}
}

// ==========================================
// SAVE SYSTEM (Menu)
// ==========================================

class GameStorage(context: Context) {
private val prefs: SharedPreferences = context.getSharedPreferences("police_save", Context.MODE_PRIVATE)

fun saveProgress(profile: OfficerProfile) {
prefs.edit().apply {
putString("name", profile.name)
putInt("age", profile.age)
putInt("skin", profile.skinIndex)
putString("rank", profile.rank.name)
putInt("xp", profile.xp)
putInt("vehicle", profile.chosenVehicleIndex)
putInt("money", profile.money)
putInt("arrests", profile.arrests)
putInt("patrols", profile.patrolsCompleted)
apply()
}
}

fun hasSavedProfile(): Boolean = prefs.contains("name")

fun loadProgress(): OfficerProfile {
val rankName = prefs.getString("rank", "CADET") ?: "CADET"
val rank = try { Rank.valueOf(rankName) } catch (_: Exception) { Rank.CADET }
return OfficerProfile(
name = prefs.getString("name", "Officer") ?: "Officer",
age = prefs.getInt("age", 25),
skinIndex = prefs.getInt("skin", 0),
rank = rank,
xp = prefs.getInt("xp", 0),
chosenVehicleIndex = prefs.getInt("vehicle", 0),
money = prefs.getInt("money", 0),
arrests = prefs.getInt("arrests", 0),
patrolsCompleted = prefs.getInt("patrols", 0)
)
}

fun resetGame() = prefs.edit().clear().apply()
}

// ==========================================
// VIEW MODEL (Menu)
// ==========================================

class GameViewModel(private val storage: GameStorage) : ViewModel() {
private val _profile = mutableStateOf(OfficerProfile())
val profile: State<OfficerProfile> = _profile

fun loadOrCreate(loadFromSave: Boolean) {
_profile.value = if (loadFromSave && storage.hasSavedProfile()) storage.loadProgress() else OfficerProfile()
}

fun createNewProfile(newProfile: OfficerProfile) {
_profile.value = newProfile
storage.saveProgress(newProfile)
}

fun performPatrol(): PatrolResult {
val current = _profile.value
val rand = Random.nextInt(100)
val (message, xpGain, moneyGain, arrests) = when {
rand < 10 -> "🚨 Major crime stopped! You earned a commendation." to 150 to 200 to 2
rand < 40 -> "✅ Routine patrol – all quiet." to 30 to 50 to 0
rand < 70 -> "⚠️ Petty crime halted. One suspect arrested." to 60 to 80 to 1
else -> "🔥 Intense pursuit! Multiple arrests." to 100 to 120 to 2
}
var newProfile = current.copy(xp = current.xp + xpGain, money = current.money + moneyGain, arrests = current.arrests + arrests, patrolsCompleted = current.patrolsCompleted + 1)
val ranks = Rank.values()
val newRank = ranks.lastOrNull { newProfile.xp >= it.xpRequired } ?: Rank.CADET
val rankUp = newRank != current.rank
val promotionBonus = if (rankUp) 250 else 0
if (rankUp) newProfile = newProfile.copy(rank = newRank, money = newProfile.money + promotionBonus)
_profile.value = newProfile
storage.saveProgress(newProfile)
return PatrolResult(message, xpGain, moneyGain + promotionBonus, arrests, rankUp, newRank, promotionBonus)
}

fun performTrafficStop(choice: Int): TrafficStopResult {
val current = _profile.value
val violations = listOf("Speeding (35 over limit)", "Reckless driving", "Expired license plate", "Running a red light")
val violation = violations.random()
val baseXP = 20
val baseMoney = 30
val (action, xpMod, moneyMod, arrestsMade) = when (choice) {
0 -> "Warn" to 1.0f to 0.5f to 0
1 -> "Issue Ticket" to 1.5f to 1.2f to 0
else -> "Make Arrest" to 2.0f to 0.8f to 1
}
val xpGain = (baseXP * xpMod).toInt()
val moneyGain = (baseMoney * moneyMod).toInt()
var newProfile = current.copy(xp = current.xp + xpGain, money = current.money + moneyGain, arrests = current.arrests + arrestsMade, patrolsCompleted = current.patrolsCompleted + 1)
val ranks = Rank.values()
val newRank = ranks.lastOrNull { newProfile.xp >= it.xpRequired } ?: Rank.CADET
val rankUp = newRank != current.rank
val promotionBonus = if (rankUp) 250 else 0
if (rankUp) newProfile = newProfile.copy(rank = newRank, money = newProfile.money + promotionBonus)
_profile.value = newProfile
storage.saveProgress(newProfile)
val message = when (choice) {
0 -> "You gave a warning to the driver for $violation." 1 -&gt; "You issued a ticket for $violation. Driver paid the fine."
else -> "You arrested the driver for `$violation. They are now in custody."
}
return TrafficStopResult(violation, action, message, xpGain, moneyGain + promotionBonus, arrestsMade, rankUp, newRank, promotionBonus)
}

fun performVipEscort(): VipEscortResult {
val current = _profile.value
val vehicle = VEHICLE_CATALOG[current.chosenVehicleIndex]
val requiredSpeed = 100 + (current.rank.ordinal * 10)
val success = vehicle.topSpeed >= requiredSpeed && current.rank.ordinal >= Rank.SERGEANT.ordinal
val (message, xpGain, moneyGain) = if (success) "VIP Escort successful! The VIP was impressed." to 120 to 180
else "VIP Escort failed. The VIP complained about slow speed." to 40 to 50
var newProfile = current.copy(xp = current.xp + xpGain, money = current.money + moneyGain, patrolsCompleted = current.patrolsCompleted + 1)
val ranks = Rank.values()
val newRank = ranks.lastOrNull { newProfile.xp >= it.xpRequired } ?: Rank.CADET
val rankUp = newRank != current.rank
val promotionBonus = if (rankUp) 250 else 0
if (rankUp) newProfile = newProfile.copy(rank = newRank, money = newProfile.money + promotionBonus)
_profile.value = newProfile
storage.saveProgress(newProfile)
return VipEscortResult(success, message, xpGain, moneyGain + promotionBonus, rankUp, newRank, promotionBonus)
}

fun resetGame() {
storage.resetGame()
_profile.value = OfficerProfile()
}

fun selectVehicle(index: Int) {
val vehicle = VEHICLE_CATALOG[index]
val current = _profile.value
if (vehicle.minRank.ordinal <= current.rank.ordinal) {
_profile.value = current.copy(chosenVehicleIndex = index)
storage.saveProgress(_profile.value)
}
}
}

class GameViewModelFactory(private val storage: GameStorage) : androidx.lifecycle.ViewModelProvider.Factory {
override fun <T : ViewModel> create(modelClass: Class<T>): T {
@Suppress("UNCHECKED_CAST")
return GameViewModel(storage) as T
}
}

// ==========================================
// MAIN ACTIVITY & SCREENS
// ==========================================

class MainActivity : ComponentActivity() {
override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
setContent {
MaterialTheme {
Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF111111)) {
MainEngine()
}
}
}
}
}

@Composable
fun MainEngine() {
val context = LocalContext.current
val storage = remember { GameStorage(context) }
val factory = remember { GameViewModelFactory(storage) }
val viewModel: GameViewModel = viewModel(factory = factory)
var screen by rememberSaveable { mutableStateOf(GameScreen.MAIN_MENU) }

when (screen) {
GameScreen.MAIN_MENU -> {
val hasSave = storage.hasSavedProfile()
MainMenuScreen(hasSave,
onContinue = { viewModel.loadOrCreate(true); screen = GameScreen.OPEN_WORLD },
onNewGame = { viewModel.loadOrCreate(false); screen = GameScreen.CHARACTER_CREATOR }
)
}
GameScreen.CHARACTER_CREATOR -> CharacterCreatorScreen { newProfile ->
viewModel.createNewProfile(newProfile)
screen = GameScreen.OPEN_WORLD
}
GameScreen.OPEN_WORLD -> OpenWorldPatrolScreen(viewModel,
onResetAndGoToMenu = { viewModel.resetGame(); screen = GameScreen.MAIN_MENU },
onStartDriving = { screen = GameScreen.DRIVING_WORLD }
)
GameScreen.DRIVING_WORLD -> WorldScreen(onBack = { screen = GameScreen.OPEN_WORLD })
}
}

// ------------------------------------------------------------
// MENU SCREENS (MainMenu, CharacterCreator, Dashboard, etc.)
// ------------------------------------------------------------

@Composable
fun MainMenuScreen(hasSave: Boolean, onContinue: () -> Unit, onNewGame: () -> Unit) {
Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
Text("Police Simulator", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
Spacer(Modifier.height(20.dp))
if (hasSave) Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
Spacer(Modifier.height(10.dp))
Button(onClick = onNewGame, modifier = Modifier.fillMaxWidth()) { Text("New Game") }
}
}

@Composable
fun CharacterCreatorScreen(onFinished: (OfficerProfile) -> Unit) {
var name by remember { mutableStateOf("Officer") }
var age by remember { mutableStateOf("25") }
var skinIndex by remember { mutableStateOf(0) }
var nameError by remember { mutableStateOf(false) }
var ageError by remember { mutableStateOf(false) }

Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
Text("Create Officer", color = Color.White, fontSize = 32.sp)
Spacer(Modifier.height(8.dp))
OutlinedTextField(value = name, onValueChange = { name = it; nameError = false }, label = { Text("Name") }, isError = nameError, supportingText = { if (nameError) Text("Name cannot be empty") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
Spacer(Modifier.height(12.dp))
OutlinedTextField(value = age, onValueChange = { newAge -> age = newAge.filter { it.isDigit() }; ageError = false }, label = { Text("Age") }, isError = ageError, supportingText = { if (ageError) Text("Enter a valid age (18‑60)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(), singleLine = true)
Spacer(Modifier.height(20.dp))
Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
SKIN_PALETTE.forEachIndexed { idx, color ->
Box(modifier = Modifier.size(56.dp).background(color, CircleShape).then(if (idx == skinIndex) Modifier.border(3.dp, Color.White, CircleShape) else Modifier).clickable { skinIndex = idx })
}
}
Spacer(Modifier.height(20.dp))
Button(onClick = {
val trimmedName = name.trim()
val ageInt = age.toIntOrNull()
when {
trimmedName.isEmpty() -> nameError = true
ageInt == null || ageInt !in 18..60 -> ageError = true
else -> onFinished(OfficerProfile(name = trimmedName, age = ageInt, skinIndex = skinIndex))
}
}, modifier = Modifier.fillMaxWidth()) { Text("Begin Patrol") }
}
}

@Composable
fun OpenWorldPatrolScreen(viewModel: GameViewModel, onResetAndGoToMenu: () -> Unit, onStartDriving: () -> Unit) {
val profile by viewModel.profile
var currentScreen by rememberSaveable { mutableStateOf("dashboard") }
var lastPatrolResult by remember { mutableStateOf<PatrolResult?>(null) }
var lastTrafficResult by remember { mutableStateOf<TrafficStopResult?>(null) }
var lastVipResult by remember { mutableStateOf<VipEscortResult?>(null) }
var showResetConfirm by remember { mutableStateOf(false) }

BackHandler(enabled = currentScreen != "dashboard") { currentScreen = "dashboard" }

if (showResetConfirm) AlertDialog(onDismissRequest = { showResetConfirm = false }, title = { Text("Reset Game") }, text = { Text("Are you sure?") },
confirmButton = { TextButton(onClick = { showResetConfirm = false; onResetAndGoToMenu() }) { Text("Yes") } },
dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") } }
)

Box(modifier = Modifier.fillMaxSize()) {
when (currentScreen) {
"dashboard" -> DashboardContent(profile,
onStandardPatrol = { lastPatrolResult = viewModel.performPatrol(); currentScreen = "patrolResult" },
onTrafficStop = { currentScreen = "trafficChoice" },
onVipEscort = { lastVipResult = viewModel.performVipEscort(); currentScreen = "vipResult" },
onOpenGarage = { currentScreen = "garage" },
onResetRequest = { showResetConfirm = true },
onFreeRoam = onStartDriving
)
"trafficChoice" -> TrafficStopChoiceScreen(onChoice = { choice -> lastTrafficResult = viewModel.performTrafficStop(choice); currentScreen = "trafficResult" }, onBack = { currentScreen = "dashboard" })
"trafficResult" -> lastTrafficResult?.let { TrafficStopResultScreen(it) { currentScreen = "dashboard" } } ?: run { currentScreen = "dashboard" }
"vipResult" -> lastVipResult?.let { VipEscortResultScreen(it) { currentScreen = "dashboard" } } ?: run { currentScreen = "dashboard" }
"patrolResult" -> lastPatrolResult?.let { PatrolResultScreen(it) { currentScreen = "dashboard" } } ?: run { currentScreen = "dashboard" }
"garage" -> GarageContent(profile, onSelectVehicle = { viewModel.selectVehicle(it) }, onBack = { currentScreen = "dashboard" })
}
}
}

@Composable
fun DashboardContent(profile: OfficerProfile, onStandardPatrol: () -> Unit, onTrafficStop: () -> Unit, onVipEscort: () -> Unit, onOpenGarage: () -> Unit, onResetRequest: () -> Unit, onFreeRoam: () -> Unit) {
val activeCar = VEHICLE_CATALOG.getOrElse(profile.chosenVehicleIndex) { VEHICLE_CATALOG.first() }
val skinColor = SKIN_PALETTE.getOrElse(profile.skinIndex) { SKIN_PALETTE.first() }
val nextRank = Rank.values().getOrNull(profile.rank.ordinal + 1)
val xpProgressText = if (nextRank != null) " {nextRank.xpRequired - profile.rank.xpRequired} XP to $`{nextRank.title}" else "MAX RANK"
val progress = if (nextRank != null) ((profile.xp - profile.rank.xpRequired).coerceAtLeast(0).toFloat() / (nextRank.xpRequired - profile.rank.xpRequired).toFloat()).coerceIn(0f,1f) else 1f

Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
Text("Welcome, ${profile.name}", color = Color.White, fontSize = 24.sp) Spacer(Modifier.height(12.dp)) Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { Box(modifier = Modifier.size(60.dp).background(skinColor, CircleShape).border(2.dp, Color.White, CircleShape)) } Spacer(Modifier.height(12.dp)) Text("Rank: $xpProgressText", color = Color.Gray)
LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
Text("Money: ${profile.money}", color = Color.Gray) Text("Patrols: ${profile.patrolsCompleted}", color = Color.Gray)
Spacer(Modifier.height(12.dp))
Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Vehicle: ${activeCar.icon} ${activeCar.name}", color = Color.White); Text("Top Speed: ${activeCar.topSpeed} mph", color = Color.White); Text("Fuel: ${String.format("%.1f", activeCar.efficiency)} mpg", color = Color.White) } }
Spacer(Modifier.height(12.dp))
Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Statistics", fontWeight = FontWeight.Bold, color = Color.White); Text("Age: ${profile.age}", color = Color.White); Text("Arrests: ${profile.arrests}", color = Color.White); Text("Patrols: `${profile.patrolsCompleted}", color = Color.White) } }
Spacer(Modifier.height(20.dp))
Button(onClick = onStandardPatrol, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))) { Text("🚔 Standard Patrol") }
Spacer(Modifier.height(8.dp))
Button(onClick = onTrafficStop, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))) { Text("🚦 Traffic Police") }
Spacer(Modifier.height(8.dp))
Button(onClick = onVipEscort, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC2185B))) { Text("🚨 VIP Escort") }
Spacer(Modifier.height(8.dp))
Button(onClick = onFreeRoam, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0))) { Text("🏁 Free Roam (Drive)") }
Spacer(Modifier.height(10.dp))
OutlinedButton(onClick = onOpenGarage, modifier = Modifier.fillMaxWidth()) { Text("Garage") }
Spacer(Modifier.height(24.dp))
Button(onClick = onResetRequest, colors = ButtonDefaults.buttonColors(containerColor = Color.Red), modifier = Modifier.fillMaxWidth()) { Text("Reset Game") }
}
}

@Composable
fun TrafficStopChoiceScreen(onChoice: (Int) -> Unit, onBack: () -> Unit) {
Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
Text("Traffic Stop", color = Color.White, fontSize = 24.sp)
Spacer(Modifier.height(16.dp))
Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
Text("You pulled over a vehicle.", color = Color.White)
Button(onClick = { onChoice(0) }, Modifier.fillMaxWidth()) { Text("⚠️ Warn") }
Button(onClick = { onChoice(1) }, Modifier.fillMaxWidth()) { Text("📝 Ticket") }
Button(onClick = { onChoice(2) }, Modifier.fillMaxWidth()) { Text("🚔 Arrest") }
OutlinedButton(onClick = onBack, Modifier.fillMaxWidth()) { Text("Cancel") }
} }
}
}

@Composable
fun TrafficStopResultScreen(result: TrafficStopResult, onDismiss: () -> Unit) {
Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
Text("Traffic Stop Report", color = Color.White, fontSize = 24.sp)
Spacer(Modifier.height(16.dp))
Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF333333))) { Column(Modifier.padding(16.dp)) {
Text("Violation:  {result.actionTaken}", color = Color.White)
Text(result.message, color = Color.White)
Spacer(Modifier.height(8.dp))
Text("Rewards", fontWeight = FontWeight.Bold, color = Color.White)
Text("⭐ XP: + {result.promotionBonus}", color = Color(0xFFFFA500))
Text("💵 Money: + {result.arrestsMade}", color = Color.White)
if (result.rankUp) Text("🎉 Promoted to $`{result.newRank.title}", color = Color(0xFFFFA500), fontWeight = FontWeight.Bold)
} }
Spacer(Modifier.height(24.dp))
Button(onClick = onDismiss, Modifier.fillMaxWidth()) { Text("Return") }
}
}

@Composable
fun VipEscortResultScreen(result: VipEscortResult, onDismiss: () -> Unit) {
Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
Text("VIP Escort Report", color = Color.White, fontSize = 24.sp)
Spacer(Modifier.height(16.dp))
Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF333333))) { Column(Modifier.padding(16.dp)) {
Text(if (result.success) "✅ SUCCESS" else "❌ FAILED", color = Color.White, fontWeight = FontWeight.Bold)
Text(result.message, color = Color.White)
Text("Rewards", fontWeight = FontWeight.Bold, color = Color.White)
Text("⭐ XP: +${result.xpGained}", color = Color(0xFF4CAF50)) if (result.promotionBonus &gt; 0) Text("🎉 Promotion Bonus: +${result.promotionBonus}", color = Color(0xFFFFA500))
Text("💵 Money: +${result.moneyGained}", color = Color(0xFF4CAF50)) if (result.rankUp) Text("🎉 Promoted to ${result.newRank.title}", color = Color(0xFFFFA500), fontWeight = FontWeight.Bold)
} }
Spacer(Modifier.height(24.dp))
Button(onClick = onDismiss, Modifier.fillMaxWidth()) { Text("Return") }
}
}

@Composable
fun PatrolResultScreen(result: PatrolResult, onDismiss: () -> Unit) {
Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
Text("Patrol Report", color = Color.White, fontSize = 24.sp)
Spacer(Modifier.height(16.dp))
Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF333333))) { Column(Modifier.padding(16.dp)) {
Text(result.message, color = Color.White)
Text("Rewards", fontWeight = FontWeight.Bold, color = Color.White)
Text("⭐ XP: +${result.xpGained}", color = Color(0xFF4CAF50)) if (result.promotionBonus &gt; 0) Text("🎉 Promotion Bonus: +${result.promotionBonus}", color = Color(0xFFFFA500))
Text("💵 Money: +${result.moneyGained}", color = Color(0xFF4CAF50)) Text("👮 Arrests: +${result.arrests}", color = Color.White)
if (result.rankUp) Text("🎉 Promoted to `${result.newRank.title}", color = Color(0xFFFFA500), fontWeight = FontWeight.Bold)
} }
Spacer(Modifier.height(24.dp))
Button(onClick = onDismiss, Modifier.fillMaxWidth()) { Text("Return") }
}
}

@Composable
fun GarageContent(profile: OfficerProfile, onSelectVehicle: (Int) -> Unit, onBack: () -> Unit) {
Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
Text("Garage", color = Color.White, fontSize = 24.sp)
Spacer(Modifier.height(12.dp))
VEHICLE_CATALOG.forEachIndexed { index, vehicle ->
val isOwned = vehicle.minRank.ordinal <= profile.rank.ordinal
val isSelected = profile.chosenVehicleIndex == index
val cardColors = when {
isSelected -> CardDefaults.cardColors(containerColor = Color(0xFF2E7D32))
isOwned -> CardDefaults.cardColors(containerColor = Color(0xFF1E3A8A))
else -> CardDefaults.cardColors(containerColor = Color.DarkGray.copy(alpha = 0.5f))
}
Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable(enabled = isOwned) { onSelectVehicle(index) }, colors = cardColors) {
Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
Column(Modifier.weight(1f)) {
Text(" {vehicle.name}", fontWeight = FontWeight.Bold, color = Color.White)
Text("Speed:  {String.format("%.1f", vehicle.efficiency)} mpg", color = Color.White)
Text("Min Rank: ${vehicle.minRank.title}", color = if (isOwned) Color.Green else Color.Red)
}
when {
isSelected -> Text("✔ EQUIPPED", color = Color.Green, fontWeight = FontWeight.Bold)
isOwned -> Text("🔓 UNLOCKED", color = Color.Cyan)
else -> Text("🔒 LOCKED", color = Color.Red)
}
}
}
}
Spacer(Modifier.height(24.dp))
Button(onClick = onBack, Modifier.fillMaxWidth()) { Text("Back") }
}
}
