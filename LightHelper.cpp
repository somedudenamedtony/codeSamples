#include "Arduino.h"
#include "FastLED.h"
#include "LightHelper.h"

//Static Patterns
LightHelper::LightHelper(CRGB *leds_, int num_leds_, int offset_)
{
    leds = leds_;
    num_leds = num_leds_;
    offset = offset_;
}

void LightHelper::SetIndividualPixel(int index, CRGB colorA, int brightness)
{
    FastLED.setBrightness(brightness);
    if (index < num_leds)
    {
        leds[index] = colorA;
    }
}

void LightHelper::SetIndividualPixel(int index, CHSV colorA)
{
    FastLED.setBrightness(255);
    if (index < num_leds)
    {
        leds[index] = colorA;
    }
}

void LightHelper::SingleColor(CRGB colorA, int brightness)
{
    FastLED.setBrightness(brightness);
    for (int i = offset; i < num_leds; i++)
    {
        leds[i] = colorA;
    }
    FastLED.show();
}

void LightHelper::SingleColor(CHSV colorA)
{
    FastLED.setBrightness(255);
    for (int i = offset; i < num_leds; i++)
    {
        leds[i] = colorA;
    }
    FastLED.show();
}

void LightHelper::MultipleColors(CRGB *colors, int num_colors, int groupCount, int brightness)
{
    FastLED.setBrightness(brightness);
    int cur_color = 0;
    int counter = offset;
    while(counter < num_leds){
        for (int groupIndex = 0; groupIndex < groupCount; groupIndex++)
        {
            leds[counter] = colors[cur_color];
            counter++;
        }
        cur_color++;
        if (cur_color == num_colors)
        {
            cur_color = 0;
        }
    }

    FastLED.show();
}

void LightHelper::MultipleColors(CHSV *colors, int num_colors, int groupCount)
{
    FastLED.setBrightness(255);
    int cur_color = 0;
    for (int i = offset; i < num_leds; i++)
    {
        for (int groupIndex = 0; groupIndex < groupCount; groupIndex++)
        {
            leds[i] = colors[cur_color];
        }
        cur_color++;
        if (cur_color == num_colors)
        {
            cur_color = 0;
        }
    }
    FastLED.show();
}

//Static Motion
void LightHelper::FadeUpWhole(int delayCount, int endBrightness)
{
    for (int i = 0; i <= endBrightness; i++)
    {
        FastLED.setBrightness(i);
        FastLED.show();
        i++;
        delay(delayCount);
    }
}
void LightHelper::FadeDownWhole(int delayCount, int endBrightness)
{
    for (int i = endBrightness; i >= 0; i--)
    {
        FastLED.setBrightness(i);
        FastLED.show();
        i--;
        delay(delayCount);
    }
}

void LightHelper::IncrementLeds()
{
    FastLED.setBrightness(255);
    CRGB endValue = leds[num_leds - 1];
    for (int i = num_leds - 1; i > offset; i--)
    {
        leds[i] = leds[i - 1];
    }
    leds[offset] = endValue;
    FastLED.show();
}