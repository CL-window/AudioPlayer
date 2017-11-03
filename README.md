### Android Audio Player
* 需求：自定义一个Android播放器
    * 可以随意选择需要播放音乐片段
    * 返回选中播放部分的音乐数据
* 分析：
    * MediaPlayer 肯定是实现不了了，使用AudioTrack
    * 需要可以选择播放的音乐片段，需要界面的支持，至少需要一个可以[双向选择的SeekBar](https://github.com/Jay-Goo/RangeSeekBar)，感谢前辈的支持
    * 需要返回选中部分的音乐数据，返回的数据是byte[]，可以选择之间返回一堆byte数组，可以写入缓存文件，然后返回缓存文件，接收方再从文件里读取；推荐第二种方式，更灵活一点，还可以自定义文件头，方便传递诸如SampleRate，ChannelCount等等参数，直接传一堆数组，如果选择播放区域很大，很吃内存。
* 实现：
    * 感谢感谢,[双向选择的SeekBar](https://github.com/Jay-Goo/RangeSeekBar)的问题顺利解决，当然也可以自己实现，但这个不是现在的主要矛盾
    * AudioTrack 播放音乐