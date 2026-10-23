package cn.plum.ember.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder

object Json {
    val gson: Gson = GsonBuilder().setLenient().create()
    val pretty: Gson = GsonBuilder().setPrettyPrinting().setLenient().create()
}
