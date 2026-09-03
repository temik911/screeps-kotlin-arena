@file:JsModule("game/prototypes/game-object")
@file:JsNonModule

package screeps.api

import kotlinx.js.JsPlainObject
import kotlin.js.definedExternally


external interface Position {
    /** The X coordinate in the room. */
    var x: Int
    /** The Y coordinate in the room. */
    var y: Int
}

/** Effect values are calculated as base * (multiplier ?: 1) + (offset ?: 0). */
@JsPlainObject
external interface EffectData {
    /** The effect multiplier. Defaults to 1. */
    var multiplier: Double?
    /** The effect offset. Defaults to 0 and is applied after the multiplier. */
    var offset: Double?
}

/** Represents an effect applied to a game object. */
external class Effect {
    /** The effect type. */
    var effectType: String
    /** The effect data. */
    var data: EffectData
    /** The game tick when the effect expires. May be omitted from effect templates. */
    var endTime: Int?
}

/**
 * Basic prototype for game objects.
 * All objects and classes are inherited from this class.
 */
external open class GameObject : Position {
    /** true if this object is live in the game at the moment. */
    var exists: Boolean

    /** The unique ID of this object that you can use in {@link getObjectById}. */
    var id: String

    /** If defined, then this object will disappear after this number of ticks. */
    var ticksToDecay: Int?

    /** The X coordinate in the room. */
    override var x: Int

    /** The Y coordinate in the room. */
    override var y: Int

    /** An array with the effects applied to this object. */
    var effects: Array<Effect>?

    /**
     * Find a position with the shortest path from this game object.
     * @param positions The positions to search among. An array of {@link GameObject} or any objects containing x and y properties.
     * @param options An object containing additional pathfinding flags.
     * @param options.costMatrix Custom navigation cost data.
     * @param options.plainCost Cost for walking on plain positions. The default is 2.
     * @param options.swampCost Cost for walking on swamp positions. The default is 10.
     * @param options.flee Instead of searching for a path to the goals this will search for a path away from the goals. The default is false.
     * @param options.maxOps The maximum allowed pathfinding operations. The default value is 50000.
     * @param options.maxCost The maximum allowed cost of the path returned. The default is Infinity.
     * @param options.heuristicWeight Weight from 1 to 9 to apply to the heuristic in the A* formula F = G + weight * H. The default value is 1.2.
     * @param options.ignore objects which should not be treated as obstacles during the search.
     * @returns the closest object from {@link positions}, or null if there was no valid positions.
     */
    fun <T : Position> findClosestByPath(
        positions: Array<T>,
        options: FindPathOptions = definedExternally,
    ): T?

    /**
     * Find a position with the shortest linear distance from this game object.
     * @param positions The positions to search among. An array of {@link GameObject} or any objects containing x and y properties.
     * @returns the closest object from {@link positions}.
     */
    fun <T : Position> findClosestByRange(positions: Array<T>): T

    /**
     * Find all objects in the specified linear range.
     * @param positions The positions to search. An array of {@link GameObject} or any objects containing x and y properties.
     * @param range The range distance.
     * @returns an array with the objects found.
     */
    fun <T : Position> findInRange(positions: Array<T>, range: Int): Array<T>

    /**
     * Find a path from this object to the given position.
     * @param pos The target position. May be {@link GameObject} or any object containing x and y properties.
     * @param options An object with additional options that are passed to {@link findPath}.
     * @param options.costMatrix Custom navigation cost data.
     * @param options.plainCost Cost for walking on plain positions. The default is 2.
     * @param options.swampCost Cost for walking on swamp positions. The default is 10.
     * @param options.flee Instead of searching for a path to the goals this will search for a path away from the goals. The default is false.
     * @param options.maxOps The maximum allowed pathfinding operations. The default value is 50000.
     * @param options.maxCost The maximum allowed cost of the path returned. The default is Infinity.
     * @param options.heuristicWeight Weight from 1 to 9 to apply to the heuristic in the A* formula F = G + weight * H. The default value is 1.2.
     * @param options.ignore objects which should not be treated as obstacles during the search.
     * @returns the path found as an array of objects containing x and y properties.
     */
    fun findPathTo(pos: Position, options: FindPathOptions = definedExternally): Array<Position>

    /**
     * Get linear range between this and target object.
     * @param pos The target object. May be {@link GameObject} or any object containing x and y properties.
     * @returns a number of squares between two objects.
     */
    fun getRangeTo(pos: Position): Int
}
