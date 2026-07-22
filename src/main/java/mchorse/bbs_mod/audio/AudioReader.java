package mchorse.bbs_mod.audio;

import mchorse.bbs_mod.audio.ogg.VorbisReader;
import mchorse.bbs_mod.audio.wav.WaveReader;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

public class AudioReader
{
    public static Wave read(AssetProvider provider, Link link) throws IOException
    {
        if (link == null)
        {
            throw new AudioDecodeException("Audio link is null");
        }

        String source = link.toString();

        if (provider == null)
        {
            throw new AudioDecodeException(source, "Asset provider is null");
        }

        if (link.path == null)
        {
            throw new UnsupportedAudioFormatException(source, "Audio path is missing");
        }

        String pathLower = link.path.toLowerCase(Locale.ROOT);

        if (!pathLower.endsWith(".wav") && !pathLower.endsWith(".ogg"))
        {
            throw new UnsupportedAudioFormatException(source, "Unsupported audio extension; expected .wav or .ogg");
        }

        /* System.out.println("Reading: " + link); */

        try (InputStream asset = provider.getAsset(link))
        {
            if (asset == null)
            {
                throw new AudioDecodeException(source, "Asset provider returned no stream");
            }

            try
            {
                Wave wave = pathLower.endsWith(".wav")
                    ? new WaveReader().read(asset, source)
                    : VorbisReader.read(link, asset);

                if (wave == null)
                {
                    throw new MalformedAudioException(source, "Decoder returned no audio");
                }

                return wave;
            }
            catch (IOException e)
            {
                throw e;
            }
            catch (RuntimeException e)
            {
                throw new MalformedAudioException(source, "Unexpected decoder failure", e);
            }
        }
    }
}
