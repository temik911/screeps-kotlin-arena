@file:JsModule("game/constants")
@file:JsNonModule

package screeps.api

external interface Constant<T>


typealias StringConstant = Constant<String>
typealias IntConstant = Constant<Int>

sealed external interface ScreepsReturnCode : IntConstant
sealed external interface BodyPartConstant : StringConstant
sealed external interface DirectionConstant : IntConstant
sealed external interface TerrainConstant : IntConstant
sealed external interface ResourceConstant : StringConstant

external object OK : ScreepsReturnCode
external object ERR_NOT_OWNER : ScreepsReturnCode
external object ERR_NO_PATH : ScreepsReturnCode
external object ERR_NAME_EXISTS : ScreepsReturnCode
external object ERR_BUSY : ScreepsReturnCode
external object ERR_NOT_FOUND : ScreepsReturnCode
external object ERR_NOT_ENOUGH_ENERGY : ScreepsReturnCode
external object ERR_NOT_ENOUGH_RESOURCES : ScreepsReturnCode
external object ERR_INVALID_TARGET : ScreepsReturnCode
external object ERR_FULL : ScreepsReturnCode
external object ERR_NOT_IN_RANGE : ScreepsReturnCode
external object ERR_INVALID_ARGS : ScreepsReturnCode
external object ERR_TIRED : ScreepsReturnCode
external object ERR_NO_BODYPART : ScreepsReturnCode
external object ERR_NOT_ENOUGH_EXTENSIONS : ScreepsReturnCode

external object MOVE: BodyPartConstant
external object RANGED_ATTACK: BodyPartConstant
external object HEAL: BodyPartConstant
external object ATTACK: BodyPartConstant
external object CARRY: BodyPartConstant
external object TOUGH: BodyPartConstant
external object WORK: BodyPartConstant

external object TOP: DirectionConstant
external object TOP_RIGHT: DirectionConstant
external object RIGHT: DirectionConstant
external object BOTTOM_RIGHT: DirectionConstant
external object BOTTOM: DirectionConstant
external object BOTTOM_LEFT: DirectionConstant
external object LEFT: DirectionConstant
external object TOP_LEFT: DirectionConstant

external object TERRAIN_PLAIN: TerrainConstant
external object TERRAIN_WALL: TerrainConstant
external object TERRAIN_SWAMP: TerrainConstant

external val BODYPART_HITS: Int

external val RANGED_ATTACK_POWER: Int
external val RANGED_ATTACK_DISTANCE_RATE: Record<Int, Int>
external val ATTACK_POWER: Int
external val HEAL_POWER: Int
external val RANGED_HEAL_POWER: Int
external val CARRY_CAPACITY: Int
external val REPAIR_POWER: Int
external val DISMANTLE_POWER: Int
external val REPAIR_COST: Int
external val DISMANTLE_COST: Int
external val HARVEST_POWER: Int
external val BUILD_POWER: Int

external val OBSTACLE_OBJECT_TYPES: Array<String>

external val TOWER_ENERGY_COST: Int
external val TOWER_RANGE: Int
external val TOWER_HITS: Int
external val TOWER_CAPACITY: Int
external val TOWER_POWER_ATTACK: Int
external val TOWER_POWER_HEAL: Int
external val TOWER_POWER_REPAIR: Int
external val TOWER_OPTIMAL_RANGE: Int
external val TOWER_FALLOFF_RANGE: Int
external val TOWER_FALLOFF: Int
external val TOWER_COOLDOWN: Int

external val BODYPART_COST: Record<BodyPartConstant, Int>

external val MAX_CREEP_SIZE: Int
external val CREEP_SPAWN_TIME: Int

external val RESOURCE_ENERGY: ResourceConstant
external val RESOURCES_ALL: Array<ResourceConstant>

external val SOURCE_ENERGY_REGEN: Int

external val RESOURCE_DECAY: Int

external val MAX_CONSTRUCTION_SITES: Int

external val CONSTRUCTION_COST: Record<String, Int>
external val STRUCTURE_PROTOTYPES: Record<String, Any>

external val CONSTRUCTION_COST_ROAD_SWAMP_RATIO: Int
external val CONSTRUCTION_COST_ROAD_WALL_RATIO: Int

external val CONTAINER_HITS: Int
external val CONTAINER_CAPACITY: Int

external val WALL_HITS: Int
external val WALL_HITS_MAX: Int

external val RAMPART_HITS: Int
external val RAMPART_HITS_MAX: Int

external val ROAD_HITS: Int
external val ROAD_WEAROUT: Int

external val EXTENSION_HITS: Int
external val EXTENSION_ENERGY_CAPACITY: Int

external val SPAWN_ENERGY_CAPACITY: Int
external val SPAWN_HITS: Int

external val EFF_CONSTRUCTION_BOOST: String
external val EFF_HEAL_BOOST: String
external val EFF_RANGED_ATTACK_BOOST: String
external val EFF_ATTACK_BOOST: String
external val EFF_WORK_BOOST: String
external val EFF_MOVE_BOOST: String
// Season 4: модификаторы эффектов (Pain and Gain и др.). Значение эффекта = base * (multiplier ?: 1) + (offset ?: 0)
external val EFF_ATTACK_MODIFIER: String
external val EFF_RANGED_ATTACK_MODIFIER: String
external val EFF_HEAL_MODIFIER: String
external val EFF_DAMAGE_TAKEN_MODIFIER: String
external val EFF_FATIGUE_MODIFIER: String
external val EFF_HITS_LOSS: String
