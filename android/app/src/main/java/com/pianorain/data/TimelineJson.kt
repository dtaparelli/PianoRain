package com.pianorain.data

import com.pianorain.domain.*
import org.json.JSONArray
import org.json.JSONObject

object TimelineJson {
    fun encode(timeline: NoteTimeline, durationUs: Long, model: String): JSONObject = JSONObject()
        .put("schema",1).put("durationUs",durationUs).put("model",model).put("timeBase","source_audio")
        .put("notes",JSONArray().also { array -> timeline.notes.forEach { n -> array.put(JSONArray(listOf(n.pitch,n.startUs,n.endUs,n.velocity,n.channel))) } })
        .put("pedals",JSONArray().also { array -> timeline.pedals.forEach { p -> array.put(JSONArray(listOf(p.timeUs,p.channel,p.value))) } })
    fun decode(root: JSONObject): NoteTimeline {
        require(root.getInt("schema")==1 && root.getString("timeBase")=="source_audio") { "Unsupported note data." }
        val duration=root.getLong("durationUs"); require(duration>0)
        val notes=root.getJSONArray("notes"); val pedals=root.getJSONArray("pedals")
        return NoteTimeline((0 until notes.length()).map { val n=notes.getJSONArray(it); NoteEvent(n.getInt(0),n.getLong(1),n.getLong(2),n.getInt(3),n.getInt(4)) }.sortedBy { it.startUs },
            (0 until pedals.length()).map { val p=pedals.getJSONArray(it); val t=p.getLong(0);val ch=p.getInt(1);val v=p.getInt(2);require(t>=0 && ch in 0..15 && v in 0..127);PedalEvent(t,ch,v) }.sortedBy { it.timeUs }).clippedTo(duration)
    }
}
